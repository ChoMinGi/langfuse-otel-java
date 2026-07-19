package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Operators;

import java.util.Objects;

/**
 * Propagates OpenTelemetry and Langfuse context across a raw Reactive Streams publisher boundary.
 *
 * <p>Use this at the closest publisher that invokes {@code onNext}, {@code onError}, or
 * {@code onComplete} from a thread that does not pass through a Reactor scheduler. Wrapping the
 * raw source before provider-side operators lets those operators observe the subscription context:</p>
 *
 * <pre>{@code
 * Flux.from(ReactorContextPropagation.wrap(rawPublisher))
 *     .map(this::providerCallback);
 * }</pre>
 *
 * <p>The bridge resolves context independently for every subscription. It does not mutate the
 * source and does not retain a thread-affine {@link Scope} between signals.</p>
 */
public final class ReactorContextPropagation {

    private ReactorContextPropagation() {
    }

    /**
     * Wraps a raw publisher so subscription, signals, request, and cancel run with the subscriber's
     * OpenTelemetry/Langfuse context. The returned publisher delegates source signals and
     * backpressure methods while applying a temporary context scope at each boundary.
     *
     * @param source raw provider publisher
     * @param <T> element type
     * @return a per-subscription context-propagating publisher
     * @throws NullPointerException when {@code source} is {@code null}
     */
    public static <T> Publisher<T> wrap(Publisher<T> source) {
        Objects.requireNonNull(source, "source");
        return subscriber -> subscribe(source, subscriber);
    }

    private static <T> void subscribe(Publisher<T> source, Subscriber<? super T> subscriber) {
        Context context;
        try {
            context = resolveContext(subscriber);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            source.subscribe(subscriber);
            return;
        }

        ReactorSchedulerContextPropagation.Lease lease =
                ReactorSchedulerContextPropagation.acquire(context);
        Subscriber<? super T> scopedSubscriber =
                ReactorSchedulerContextPropagation.scopeSubscriber(subscriber, lease);
        if (!lease.hasSignalLifecycleOwner()) {
            // A nonfatal adapter setup failure must not leave a lease without a terminal owner.
            lease.close();
            source.subscribe(subscriber);
            return;
        }
        Scope scope = null;
        try {
            scope = lease.context().makeCurrent();
        } catch (Throwable failure) {
            lease.close();
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            source.subscribe(subscriber);
            return;
        }

        try {
            source.subscribe(scopedSubscriber);
        } catch (Throwable failure) {
            lease.close();
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            Operators.error(subscriber, failure);
        } finally {
            try {
                closeScopeQuietly(scope);
            } catch (Throwable closeFailure) {
                lease.close();
                InstrumentationFailureSupport.rethrowIfFatal(closeFailure);
            }
        }
    }

    private static Context resolveContext(Subscriber<?> subscriber) {
        Context context = Context.current();
        if (!(subscriber instanceof CoreSubscriber)) {
            return context;
        }

        reactor.util.context.ContextView reactorContext =
                ((CoreSubscriber<?>) subscriber).currentContext();
        context = reactorContext.getOrDefault(Context.class, context);
        LangfuseTraceContext traceContext = reactorContext.getOrDefault(
                LangfuseContext.reactorContextKey(), null);
        if (traceContext != null) {
            context = LangfuseContext.storeIn(context, traceContext);
        }
        return context;
    }

    private static void closeScopeQuietly(Scope scope) {
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
