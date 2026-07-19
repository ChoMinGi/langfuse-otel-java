package io.github.chomingi.langfuse.otel.spring.annotation;

import io.github.chomingi.langfuse.otel.ContentCapturePolicy;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Loaded reflectively only when Reactor is present on the consumer classpath. */
final class ReactorGenerationObservation {

    private static final Logger log = LoggerFactory.getLogger(ReactorGenerationObservation.class);

    private ReactorGenerationObservation() {
    }

    static boolean supportsReturnType(Class<?> returnType) {
        return Publisher.class.isAssignableFrom(returnType);
    }

    static Object wrap(Object source,
                       Class<?> declaredReturnType,
                       LangfuseOtel langfuseOtel,
                       Context capturedParent,
                       LangfuseTraceContext capturedTraceContext,
                       String name,
                       String operation,
                       String model,
                       String system) {
        if (!(source instanceof Publisher)) {
            return source;
        }

        Object wrapped;
        if (source instanceof Mono) {
            wrapped = Mono.deferContextual(reactorContext -> {
                SubscriptionObservation subscriptionObservation;
                try {
                    subscriptionObservation = start(
                            reactorContext, langfuseOtel, capturedParent, capturedTraceContext,
                            name, operation, model, system);
                } catch (Throwable failure) {
                    GenerationObservation.rethrowIfFatal(failure);
                    log.debug("Langfuse reactive observation setup failed, subscribing without tracing", failure);
                    return Mono.from((Publisher<?>) source);
                }
                GenerationObservation observation = subscriptionObservation.observation();
                SchedulerPropagationLease propagation = null;
                try {
                    propagation = SchedulerBridge.acquire(observation.spanContext());
                    SchedulerPropagationLease activePropagation = propagation;
                    AtomicBoolean completedSuccessfully = new AtomicBoolean(false);
                    AtomicReference<Object> successfulOutput = new AtomicReference<>();
                    AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
                    Publisher<Object> scopedSource = scopeSubscription(
                            (Publisher<?>) source, observation,
                            subscriptionObservation.traceContext(), activePropagation);
                    return Mono.from(scopedSource)
                            .doOnSuccess(value -> {
                                successfulOutput.set(value);
                                completedSuccessfully.set(true);
                            })
                            .doOnError(terminalFailure::set)
                            .doFinally(ignored -> {
                                try {
                                    Throwable failure = terminalFailure.getAndSet(null);
                                    if (failure != null) {
                                        observation.completeExceptionally(failure);
                                    } else if (completedSuccessfully.get()) {
                                        observation.completeSuccessfully(
                                                successfulOutput.getAndSet(null));
                                    } else {
                                        observation.end();
                                    }
                                } finally {
                                    successfulOutput.set(null);
                                    activePropagation.closeFallback();
                                    observation.end();
                                }
                            });
                } catch (Throwable failure) {
                    cleanupSetupFailure(propagation, observation);
                    GenerationObservation.rethrowIfFatal(failure);
                    log.debug("Langfuse Reactor propagation setup failed, subscribing without tracing", failure);
                    return Mono.from((Publisher<?>) source);
                }
            });
        } else {
            wrapped = Flux.deferContextual(reactorContext -> {
                SubscriptionObservation subscriptionObservation;
                try {
                    subscriptionObservation = start(
                            reactorContext, langfuseOtel, capturedParent, capturedTraceContext,
                            name, operation, model, system);
                } catch (Throwable failure) {
                    GenerationObservation.rethrowIfFatal(failure);
                    log.debug("Langfuse reactive observation setup failed, subscribing without tracing", failure);
                    return Flux.from((Publisher<?>) source);
                }
                GenerationObservation observation = subscriptionObservation.observation();
                SchedulerPropagationLease propagation = null;
                try {
                    ContentCapturePolicy capturePolicy =
                            langfuseOtel.getContentCapturePolicy();
                    boolean captureOutput = capturePolicy.isOutputCaptureEnabled();
                    int maxLength = captureOutput ? capturePolicy.getMaxLength() : 0;
                    AtomicReference<String> lastOutput = captureOutput
                            ? new AtomicReference<>()
                            : null;
                    AtomicBoolean completedSuccessfully = new AtomicBoolean(false);
                    AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
                    propagation = SchedulerBridge.acquire(observation.spanContext());
                    SchedulerPropagationLease activePropagation = propagation;
                    Publisher<Object> scopedSource = scopeSubscription(
                            (Publisher<?>) source, observation,
                            subscriptionObservation.traceContext(), activePropagation);
                    Flux<Object> observedSource = Flux.from(scopedSource);
                    if (lastOutput != null) {
                        observedSource = observedSource.doOnNext(
                                value -> lastOutput.set(toBoundedString(value, maxLength)));
                    }
                    return observedSource
                            .doOnComplete(() -> completedSuccessfully.set(true))
                            .doOnError(terminalFailure::set)
                            .doFinally(ignored -> {
                                try {
                                    Throwable failure = terminalFailure.getAndSet(null);
                                    if (failure != null) {
                                        observation.completeExceptionally(failure);
                                    } else if (completedSuccessfully.get()) {
                                        observation.completeSuccessfully(
                                                lastOutput == null
                                                        ? null
                                                        : lastOutput.getAndSet(null));
                                    } else {
                                        observation.end();
                                    }
                                } finally {
                                    activePropagation.closeFallback();
                                    if (lastOutput != null) {
                                        lastOutput.set(null);
                                    }
                                    observation.end();
                                }
                            });
                } catch (Throwable failure) {
                    cleanupSetupFailure(propagation, observation);
                    GenerationObservation.rethrowIfFatal(failure);
                    log.debug("Langfuse Reactor propagation setup failed, subscribing without tracing", failure);
                    return Flux.from((Publisher<?>) source);
                }
            });
        }

        return declaredReturnType.isInstance(wrapped) ? wrapped : source;
    }

    private static void cleanupSetupFailure(SchedulerPropagationLease propagation,
                                            GenerationObservation observation) {
        try {
            if (propagation != null) {
                propagation.forceClose();
            }
        } finally {
            observation.end();
        }
    }

    private static SubscriptionObservation start(
            reactor.util.context.ContextView reactorContext,
            LangfuseOtel langfuseOtel,
            Context capturedParent,
            LangfuseTraceContext capturedTraceContext,
            String name,
            String operation,
            String model,
            String system) {
        Context parent = reactorContext.getOrDefault(Context.class, capturedParent);
        LangfuseTraceContext traceContext = reactorContext.getOrDefault(
                LangfuseContext.reactorContextKey(), null);
        if (traceContext == null) {
            traceContext = LangfuseContext.from(parent);
        }
        if (traceContext == null) {
            traceContext = capturedTraceContext;
        }
        return new SubscriptionObservation(
                GenerationObservation.start(
                        langfuseOtel, parent, traceContext, name, operation, model, system),
                traceContext);
    }

    private static reactor.util.context.Context enrich(reactor.util.context.Context context,
                                                        Context propagatedContext,
                                                        LangfuseTraceContext capturedTraceContext) {
        reactor.util.context.Context enriched = context.put(Context.class, propagatedContext);
        if (capturedTraceContext != null && !context.hasKey(LangfuseContext.reactorContextKey())) {
            enriched = enriched.put(LangfuseContext.reactorContextKey(), capturedTraceContext);
        }
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private static Publisher<Object> scopeSubscription(Publisher<?> source,
                                                       GenerationObservation observation,
                                                       LangfuseTraceContext subscriptionTraceContext,
                                                       SchedulerPropagationLease propagation) {
        return subscriber -> {
            Scope scope;
            try {
                scope = propagation.context().makeCurrent();
            } catch (Throwable failure) {
                propagation.forceClose();
                observation.end();
                GenerationObservation.rethrowIfFatal(failure);
                log.debug("Langfuse reactive observation scope setup failed, subscribing without tracing", failure);
                ((Publisher<Object>) source).subscribe((Subscriber<? super Object>) subscriber);
                return;
            }

            try {
                Flux.from((Publisher<Object>) source)
                        .contextWrite(context -> enrich(
                                context, propagation.context(), subscriptionTraceContext))
                        .subscribe((Subscriber<? super Object>) SchedulerBridge.scopeSubscriber(
                                subscriber, propagation));
            } catch (Throwable failure) {
                propagation.forceClose();
                try {
                    GenerationObservation.rethrowIfFatal(failure);
                } catch (Throwable fatalFailure) {
                    observation.end();
                    throw fatalFailure;
                }
                Operators.error(subscriber, failure);
            } finally {
                try {
                    GenerationObservation.closeScopeQuietly(scope);
                } catch (Throwable closeFailure) {
                    propagation.forceClose();
                    observation.end();
                    GenerationObservation.rethrowIfFatal(closeFailure);
                }
            }
        };
    }

    private static final class SubscriptionObservation {

        private final GenerationObservation observation;
        private final LangfuseTraceContext traceContext;

        private SubscriptionObservation(GenerationObservation observation,
                                        LangfuseTraceContext traceContext) {
            this.observation = observation;
            this.traceContext = traceContext;
        }

        private GenerationObservation observation() {
            return observation;
        }

        private LangfuseTraceContext traceContext() {
            return traceContext;
        }
    }

    private static String toBoundedString(Object value, int maxLength) {
        try {
            String rendered = value instanceof String ? (String) value : String.valueOf(value);
            if (rendered == null || rendered.length() > maxLength) {
                return null;
            }
            return rendered;
        } catch (Throwable failure) {
            GenerationObservation.rethrowIfFatal(failure);
            // Content conversion is fail-closed and must not affect publisher behavior.
            return null;
        }
    }

    private static final class SchedulerPropagationLease {

        private final Object delegate;
        private final Context context;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private SchedulerPropagationLease(Object delegate, Context context) {
            this.delegate = delegate;
            this.context = context;
        }

        private Context context() {
            return context;
        }

        private void forceClose() {
            if (delegate != null && closed.compareAndSet(false, true)) {
                SchedulerBridge.forceClose(delegate);
            }
        }

        private void closeFallback() {
            if (delegate != null && closed.compareAndSet(false, true)) {
                SchedulerBridge.closeFallback(delegate);
            }
        }
    }

    /** Reflection keeps the annotation bridge isolated from starter-internal Reactor helpers. */
    private static final class SchedulerBridge {

        private static final Method ACQUIRE;
        private static final Method CONTEXT;
        private static final Method FORCE_CLOSE;
        private static final Method CLOSE_FALLBACK;
        private static final Method SCOPE_SUBSCRIBER;

        static {
            Method acquire = null;
            Method context = null;
            Method forceClose = null;
            Method closeFallback = null;
            Method scopeSubscriber = null;
            try {
                Class<?> bridge = Class.forName(
                        "io.github.chomingi.langfuse.otel.spring.ReactorSchedulerContextPropagation",
                        false,
                        ReactorGenerationObservation.class.getClassLoader());
                acquire = bridge.getDeclaredMethod("acquireForBridge", Context.class);
                context = bridge.getDeclaredMethod("contextForBridge", Object.class);
                forceClose = bridge.getDeclaredMethod("closeForBridge", Object.class);
                closeFallback = bridge.getDeclaredMethod(
                        "closeFallbackForBridge", Object.class);
                scopeSubscriber = bridge.getDeclaredMethod(
                        "scopeSubscriberForBridge", Subscriber.class, Object.class);
                acquire.setAccessible(true);
                context.setAccessible(true);
                forceClose.setAccessible(true);
                closeFallback.setAccessible(true);
                scopeSubscriber.setAccessible(true);
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError
                     | RuntimeException ignored) {
                // Scheduler propagation is optional and observation remains subscription-scoped.
                // Do not retain a partially accessible method set: invoking acquire without a
                // callable close method could register a global hook that this bridge cannot release.
                acquire = null;
                context = null;
                forceClose = null;
                closeFallback = null;
                scopeSubscriber = null;
            }
            ACQUIRE = acquire;
            CONTEXT = context;
            FORCE_CLOSE = forceClose;
            CLOSE_FALLBACK = closeFallback;
            SCOPE_SUBSCRIBER = scopeSubscriber;
        }

        private static SchedulerPropagationLease acquire(Context baseContext) {
            if (ACQUIRE == null || CONTEXT == null
                    || FORCE_CLOSE == null || CLOSE_FALLBACK == null) {
                return new SchedulerPropagationLease(null, baseContext);
            }

            Object delegate = null;
            try {
                delegate = ACQUIRE.invoke(null, baseContext);
                Context propagatedContext = (Context) CONTEXT.invoke(null, delegate);
                return new SchedulerPropagationLease(delegate, propagatedContext);
            } catch (InvocationTargetException failure) {
                forceClose(delegate);
                Throwable cause = failure.getCause();
                GenerationObservation.rethrowIfFatal(cause);
                log.debug("Langfuse Reactor scheduler propagation setup failed", cause);
            } catch (IllegalAccessException | RuntimeException failure) {
                forceClose(delegate);
                GenerationObservation.rethrowIfFatal(failure);
                log.debug("Langfuse Reactor scheduler propagation setup failed", failure);
            }
            return new SchedulerPropagationLease(null, baseContext);
        }

        private static void forceClose(Object delegate) {
            invokeClose(delegate, FORCE_CLOSE);
        }

        private static void closeFallback(Object delegate) {
            invokeClose(delegate, CLOSE_FALLBACK);
        }

        private static void invokeClose(Object delegate, Method closeMethod) {
            if (delegate == null || closeMethod == null) {
                return;
            }
            try {
                closeMethod.invoke(null, delegate);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                GenerationObservation.rethrowIfFatal(cause);
                log.debug("Langfuse Reactor scheduler propagation cleanup failed", cause);
            } catch (IllegalAccessException | RuntimeException failure) {
                GenerationObservation.rethrowIfFatal(failure);
                log.debug("Langfuse Reactor scheduler propagation cleanup failed", failure);
            }
        }

        private static Subscriber<?> scopeSubscriber(Subscriber<?> subscriber,
                                                      SchedulerPropagationLease propagation) {
            if (SCOPE_SUBSCRIBER == null || propagation.delegate == null) {
                return subscriber;
            }
            try {
                return (Subscriber<?>) SCOPE_SUBSCRIBER.invoke(
                        null, subscriber, propagation.delegate);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                GenerationObservation.rethrowIfFatal(cause);
                log.debug("Langfuse Reactor signal propagation setup failed", cause);
            } catch (IllegalAccessException | RuntimeException failure) {
                GenerationObservation.rethrowIfFatal(failure);
                log.debug("Langfuse Reactor signal propagation setup failed", failure);
            }
            return subscriber;
        }
    }
}
