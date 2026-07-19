package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;

/** Restores a span for source subscription, raw Reactive Streams signals, and Reactor Context. */
final class ReactorSpanContextBridge {

    private ReactorSpanContextBridge() {
    }

    static <T> Flux<T> bridge(Flux<T> source,
                              Context spanContext,
                              LangfuseTraceContext traceContext) {
        return Flux.defer(() -> {
            ReactorSchedulerContextPropagation.Lease lease =
                    ReactorSchedulerContextPropagation.acquire(spanContext);
            Context propagatedContext = lease.context();
            Publisher<T> scopedSource = subscriber -> subscribeInScope(
                    source, subscriber, propagatedContext, lease);
            try {
                return Flux.from(scopedSource)
                        .doFinally(ignored -> lease.closeFallback())
                        .contextWrite(context -> {
                            reactor.util.context.Context enriched =
                                    context.put(Context.class, propagatedContext);
                            if (traceContext != null
                                    && !context.hasKey(LangfuseContext.reactorContextKey())) {
                                enriched = enriched.put(
                                        LangfuseContext.reactorContextKey(), traceContext);
                            }
                            return enriched;
                        });
            } catch (Throwable failure) {
                lease.close();
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                return source;
            }
        });
    }

    private static <T> void subscribeInScope(Flux<T> source,
                                             Subscriber<? super T> subscriber,
                                             Context spanContext,
                                             ReactorSchedulerContextPropagation.Lease lease) {
        Scope scope = null;
        try {
            scope = spanContext.makeCurrent();
        } catch (Throwable failure) {
            lease.close();
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            source.subscribe(subscriber);
            return;
        }
        try {
            source.subscribe(ReactorSchedulerContextPropagation.scopeSubscriber(
                    subscriber, lease));
        } catch (Throwable failure) {
            lease.close();
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            Operators.error(subscriber, failure);
        } finally {
            try {
                closeQuietly(scope);
            } catch (Throwable closeFailure) {
                lease.close();
                InstrumentationFailureSupport.rethrowIfFatal(closeFailure);
            }
        }
    }

    private static void closeQuietly(Scope scope) {
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
        }
    }
}
