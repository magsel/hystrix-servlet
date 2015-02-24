package com.signicat.hystrix.servlet;

import com.netflix.hystrix.Hystrix;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.contrib.servopublisher.HystrixServoMetricsPublisher;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.strategy.HystrixPlugins;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import rx.Observable;
import rx.Observer;

/**
 * Wrapper class for executing any {@link javax.servlet.http.HttpServlet} within a Hystrix thread pool. Subclass
 * this, create a no-arg constructor in the subclass, and pass the HttpServlet to wrap to the superclass. Then
 * configure the new subclass to run in web.xml, instead of the old (wrapped) servlet. If you want to control
 * which thread pool a request ends up executing within, make the wrapped servlet implement
 * {@link com.signicat.hystrix.servlet.HystrixAwareServlet#getCommandGroupKey(javax.servlet.http.HttpServletRequest)}.
 *
 * @author Einar Rosenvinge &lt;einros@signicat.com&gt;
 */
@WebServlet(asyncSupported = true)
public class AsyncWrapperServlet extends HttpServlet {

    public static final String DEFAULT_COMMAND_GROUP_KEY = "default";
    private static final Logger log = Logger.getLogger(AsyncWrapperServlet.class.getName());
    public  static final long DEFAULT_TIMEOUT = 50000L;
    private static final int HYSTRIX_ADDED_TIMEOUT_DELAY = 10000;  //we want the servlet container to time things out before Hystrix does
    public  static final int DEFAULT_CORE_POOL_SIZE = 100;
    private final HttpServlet wrappedServlet;
    private final long timeoutMillis;
    private final int corePoolSize;

    static {
        HystrixPlugins.getInstance().registerMetricsPublisher(HystrixServoMetricsPublisher.getInstance());
    }

    public AsyncWrapperServlet(final HttpServlet wrappedServlet) {
        this(wrappedServlet, DEFAULT_TIMEOUT, DEFAULT_CORE_POOL_SIZE);
    }

    public AsyncWrapperServlet(final HttpServlet wrappedServlet, final long timeoutMillis, final int corePoolSize) {
        this.wrappedServlet = wrappedServlet;
        this.timeoutMillis = timeoutMillis;
        this.corePoolSize = corePoolSize;
    }

    @Override
    public void init() throws ServletException {
        wrappedServlet.init();
    }

    @Override
    public void destroy() {
        Hystrix.reset(5, TimeUnit.SECONDS);
        wrappedServlet.destroy();
    }

    @Override
    public void service(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(timeoutMillis);
        TimeoutAwareHttpServletRequest timeoutAwareHttpServletReq = new TimeoutAwareHttpServletRequest(req);
        TimeoutAwareHttpServletResponse timeoutAwareHttpServletResp = new TimeoutAwareHttpServletResponse(resp);
        asyncContext.addListener(new BaseServletAsyncListener(timeoutAwareHttpServletReq,
                                                              timeoutAwareHttpServletResp));

        Runnable runBefore = onBeforeCommandSubmit();
        Runnable runAfter = onAfterCommandExecute();

        final String key = getCommandGroupKey(wrappedServlet, req);
        log.log(Level.INFO, "Scheduling Hystrix command with key '" + key + "'");

        //double thread pool size for pool with magic name 'default'
        final int size = DEFAULT_COMMAND_GROUP_KEY.equals(key) ? (corePoolSize * 2) : corePoolSize;
        final int queueSize = corePoolSize * 4;
        final int queueRejectionSize = corePoolSize * 2;

        BaseServletCommand command =
                new BaseServletCommand(
                        HystrixCommand.Setter
                                .withGroupKey(
                                        HystrixCommandGroupKey.Factory.asKey(key))
                                .andCommandKey(HystrixCommandKey.Factory.asKey(key))
                                .andCommandPropertiesDefaults(
                                        HystrixCommandProperties.Setter()
                                                .withExecutionIsolationThreadTimeoutInMilliseconds(
                                                        (int) timeoutMillis + HYSTRIX_ADDED_TIMEOUT_DELAY)
                                                .withCircuitBreakerEnabled(false)
                                                .withExecutionIsolationStrategy(
                                                        HystrixCommandProperties.ExecutionIsolationStrategy.THREAD)
                                                .withFallbackEnabled(false))
                                .andThreadPoolPropertiesDefaults(
                                        HystrixThreadPoolProperties.Setter()
                                                .withCoreSize(size)
                                                .withMaxQueueSize(queueSize)
                                                .withQueueSizeRejectionThreshold(queueRejectionSize)),
                        timeoutAwareHttpServletReq,
                        timeoutAwareHttpServletResp,
                        runBefore,
                        runAfter);

        Observable<Object> observable = command.observe();
        observable.subscribe(new BaseServletObserver(asyncContext));

        onAfterCommandSubmit();
    }

    protected String getCommandGroupKey(HttpServlet srv, HttpServletRequest req) {
        if (srv instanceof HystrixAwareServlet) {
            return ((HystrixAwareServlet) srv).getCommandGroupKey(req);
        }
        return DEFAULT_COMMAND_GROUP_KEY;
    }

    /**
     * Note that this is called from
     * {@link com.signicat.hystrix.servlet.AsyncWrapperServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)},
     * i.e. by the servlet container thread. The Runnable returned is called by the
     * Hystrix thread (in a try-catch block, with logging), at the beginning of
     * {@link com.netflix.hystrix.HystrixCommand#run()}.
     */
    protected Runnable onBeforeCommandSubmit() {
        return new Runnable() {
            @Override
            public void run() {
            }
        };
    }

    /**
     * Useful? for subclasses, but really mostly for testing. Note that this is called from
     * {@link com.signicat.hystrix.servlet.AsyncWrapperServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)},
     * i.e. by the servlet container thread.
     */
    protected void onAfterCommandSubmit() {
    }

    /**
     * Note that this is called from
     * {@link com.signicat.hystrix.servlet.AsyncWrapperServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)},
     * i.e. by the servlet container thread. The Runnable returned is called by the
     * Hystrix thread (in a try-catch block, with logging, inside a finally block), at the end of
     * {@link com.netflix.hystrix.HystrixCommand#run()}.
     */
    protected Runnable onAfterCommandExecute() {
        return new Runnable() {
            @Override
            public void run() {
            }
        };
    }

    /**
     * Override this method if you want to do some extra work when
     * {@link javax.servlet.AsyncContext#complete()} has been called.
     *
     * @param asyncEvent the AsyncEvent indicating that an asynchronous operation has been completed
     * @throws IOException if an I/O related error has occurred during the processing of the given AsyncEvent
     */
    protected void onServletCompleted(AsyncEvent asyncEvent) throws IOException {
    }

    /**
     * Override this method if you want to do some extra work when
     * the servlet container has timed out the request. It is HIGHLY recommended to call
     * the implementation in this superclass as well, to be certain that
     * {@link javax.servlet.AsyncContext#complete()} is called. Failure to do so will result in strange errors.
     *
     * @param asyncEvent the AsyncEvent indicating that an asynchronous operation has timed out
     * @throws IOException if an I/O related error has occurred during the processing of the given AsyncEvent
     */
    @SuppressWarnings({"EmptyCatchBlock", "ThrowableResultOfMethodCallIgnored"})
    protected void onServletTimeout(AsyncEvent asyncEvent) throws IOException {
        try {
            final String msg = "Timeout from async listener";
            log.log(Level.FINE, msg, asyncEvent.getThrowable());
            ((HttpServletResponse) asyncEvent.getAsyncContext().getResponse())
                    .sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, msg);
        } catch (Exception e) {
        } finally {
            try {
                asyncEvent.getAsyncContext().complete();
            } catch (Exception e) {
            }
        }
    }

    /**
     * Override this method if you want to do some extra work when
     * the servlet container has caught an exception from
     * {@link com.signicat.hystrix.servlet.AsyncWrapperServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)}.
     * It is HIGHLY recommended to call
     * the implementation in this superclass as well, to be certain that
     * {@link javax.servlet.AsyncContext#complete()} is called. Failure to do so will result in strange errors.
     *
     * @param asyncEvent the AsyncEvent indicating that an asynchronous operation has failed to complete
     * @throws IOException if an I/O related error has occurred during the processing of the given AsyncEvent
     */
    @SuppressWarnings({"EmptyCatchBlock", "ThrowableResultOfMethodCallIgnored"})
    protected void onServletError(AsyncEvent asyncEvent) throws IOException {
        try {
            final String msg = "Error from async listener";
            log.log(Level.WARNING, msg, asyncEvent.getThrowable());
            ((HttpServletResponse) asyncEvent.getAsyncContext().getResponse())
                    .sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg);
        } catch (Exception e) {
        } finally {
            try {
                asyncEvent.getAsyncContext().complete();
            } catch (Exception e) {
            }
        }
    }

    /**
     * Override this method if you want to do some extra work when
     * Hystrix is notified that the processing of the request/response is done.
     * It is HIGHLY recommended to call
     * the implementation in this superclass as well, to be certain that
     * {@link javax.servlet.AsyncContext#complete()} is called. Failure to do so will result in strange errors.
     *
     * @param asyncContext The AsyncContext to complete.
     */
    @SuppressWarnings("EmptyCatchBlock")
    protected void onHystrixCompleted(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (Exception e) {
        }
    }

    /**
     * Override this method if you want to do some extra work when
     * Hystrix has caught an exception from {@link com.netflix.hystrix.HystrixCommand#run()}, or if the
     * operation has timed out.
     * It is HIGHLY recommended to call
     * the implementation in this superclass as well, to be certain that
     * {@link javax.servlet.AsyncContext#complete()} is called. Failure to do so will result in strange errors.
     *
     * @param asyncContext The AsyncContext to complete.
     * @param throwable The Throwable caught.
     */
    @SuppressWarnings("EmptyCatchBlock")
    protected void onHystrixError(AsyncContext asyncContext, Throwable throwable) {
        try {
            final HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
            final String msg = "Error from async observer";
            if (throwable instanceof HystrixRuntimeException) {
                HystrixRuntimeException hre = (HystrixRuntimeException) throwable;
                switch (hre.getFailureType()) {
                    case TIMEOUT:
                        log.log(Level.FINE, msg, throwable);
                        response.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, msg);
                        break;
                    case COMMAND_EXCEPTION:
                        log.log(Level.WARNING, msg, throwable);
                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg);
                        break;
                    default:
                        log.log(Level.FINE, msg, throwable);
                        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, msg);
                        break;
                }
            } else {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg);
            }
        } catch (Exception e) {
        }
        try {
            asyncContext.complete();
        } catch (Exception e) {
        }
    }

    /**
     * Override this method if you want to do some extra work when
     * Hystrix is notified that the processing of the request/response is done.
     * It is HIGHLY recommended to call
     * the implementation in this superclass as well, to be certain that
     * {@link javax.servlet.AsyncContext#complete()} is called. Failure to do so will result in strange errors.
     *
     * @param asyncContext The AsyncContext to complete.
     * @param o In our implementation, a dummy object that is not useful in any way.
     */
    @SuppressWarnings("EmptyCatchBlock")
    protected void onHystrixNext(AsyncContext asyncContext, Object o) {
        try {
            asyncContext.complete();
        } catch (Exception e) {
        }
    }

    private class BaseServletAsyncListener implements AsyncListener {
        private final TimeoutAwareHttpServletRequest timeoutAwareHttpServletReq;
        private final TimeoutAwareHttpServletResponse timeoutAwareHttpServletResp;

        private BaseServletAsyncListener(TimeoutAwareHttpServletRequest timeoutAwareHttpServletReq,
                                        TimeoutAwareHttpServletResponse timeoutAwareHttpServletResp) {
            this.timeoutAwareHttpServletReq = timeoutAwareHttpServletReq;
            this.timeoutAwareHttpServletResp = timeoutAwareHttpServletResp;
        }

        @Override
        public void onComplete(AsyncEvent asyncEvent) throws IOException {
            timeoutAwareHttpServletReq.resetWrapped();
            timeoutAwareHttpServletResp.resetWrapped();
            onServletCompleted(asyncEvent);
        }

        @Override
        public void onTimeout(AsyncEvent asyncEvent) throws IOException {
            timeoutAwareHttpServletReq.resetWrapped();
            timeoutAwareHttpServletResp.resetWrapped();
            onServletTimeout(asyncEvent);
        }

        @Override
        public void onError(AsyncEvent asyncEvent) throws IOException {
            timeoutAwareHttpServletReq.resetWrapped();
            timeoutAwareHttpServletResp.resetWrapped();
            onServletError(asyncEvent);
        }

        @Override
        public void onStartAsync(AsyncEvent asyncEvent) throws IOException {
        }
    }

    private class BaseServletCommand extends HystrixCommand<Object> {
        private final TimeoutAwareHttpServletRequest timeoutAwareHttpServletReq;
        private final TimeoutAwareHttpServletResponse timeoutAwareHttpServletResp;
        private final Runnable runBefore;
        private final Runnable runAfter;

        private BaseServletCommand(Setter setter,
                                  TimeoutAwareHttpServletRequest timeoutAwareHttpServletReq,
                                  TimeoutAwareHttpServletResponse timeoutAwareHttpServletResp,
                                  Runnable runBefore, Runnable runAfter) {
            super(setter);
            this.timeoutAwareHttpServletReq = timeoutAwareHttpServletReq;
            this.timeoutAwareHttpServletResp = timeoutAwareHttpServletResp;
            this.runBefore = runBefore;
            this.runAfter = runAfter;
        }

        @Override
        public Object run() throws Exception {
            try {
                runBefore.run();
            } catch (Exception e) {
                log.log(Level.INFO, "Exception in Hystrix pre-execute hook", e);
            }

            try {
                wrappedServlet.service(timeoutAwareHttpServletReq, timeoutAwareHttpServletResp);
            } finally {
                try {
                    runAfter.run();
                } catch (Exception e) {
                    log.log(Level.INFO, "Exception in Hystrix post-execute hook", e);
                }
            }
            return new Object();
        }
    }

    private class BaseServletObserver implements Observer<Object> {
        private final AsyncContext asyncContext;

        private BaseServletObserver(AsyncContext asyncContext) {
            this.asyncContext = asyncContext;
        }

        @Override
        public void onCompleted() {
            onHystrixCompleted(asyncContext);
        }

        @Override
        public void onError(Throwable throwable) {
            onHystrixError(asyncContext, throwable);
        }

        @Override
        public void onNext(Object o) {
            onHystrixNext(asyncContext, o);
        }
    }

}
