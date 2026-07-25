package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Root span of a Langfuse trace. Contains child spans and generations.
 * Automatically inherits userId, sessionId, tags from {@link LangfuseContext}.
 *
 * <p>The trace is a synchronous, thread-bound scope. Close it on the thread where it was
 * created, after all child spans and generations have been closed.</p>
 */
public class LangfuseTrace extends AbstractLangfuseSpan {

    private final Tracer tracer;
    private final LangfuseTraceState traceState;

    LangfuseTrace(Tracer tracer, String name) {
        this(tracer, name, traceBinding(name));
    }

    private LangfuseTrace(Tracer tracer, String name, TraceBinding binding) {
        super(startSpan(tracer, name, binding), name, binding.traceState, binding.owner);
        this.tracer = tracer;
        this.traceState = binding.traceState;
    }

    private static Span startSpan(Tracer tracer, String name, TraceBinding binding) {
        return tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(
                        LangfuseAttributes.TRACE_NAME,
                        binding.traceState.snapshot().getTraceName())
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "span")
                .startSpan();
    }

    private static TraceBinding traceBinding(String name) {
        LangfuseTraceState current = LangfuseContext.traceStateFrom(
                io.opentelemetry.context.Context.current());
        if (current != null) return new TraceBinding(current, false);

        LangfuseTraceContext initial = LangfuseContext.current()
                .toBuilder()
                .traceName(name)
                .build();
        return new TraceBinding(new LangfuseTraceState(initial, true), true);
    }

    private static final class TraceBinding {
        private final LangfuseTraceState traceState;
        private final boolean owner;

        private TraceBinding(LangfuseTraceState traceState, boolean owner) {
            this.traceState = traceState;
            this.owner = owner;
        }
    }

    /**
     * Records the trace user identifier.
     *
     * @param userId user identifier
     * @return this trace
     */
    public LangfuseTrace userId(String userId) {
        span.setAttribute(LangfuseAttributes.TRACE_USER_ID, userId);
        traceState.userId(userId);
        return this;
    }

    /**
     * Records the trace session identifier.
     *
     * @param sessionId session identifier
     * @return this trace
     */
    public LangfuseTrace sessionId(String sessionId) {
        span.setAttribute(LangfuseAttributes.TRACE_SESSION_ID, sessionId);
        traceState.sessionId(sessionId);
        return this;
    }

    /**
     * Records trace tags.
     *
     * @param tags trace tags; must not be {@code null}
     * @return this trace
     * @throws NullPointerException if {@code tags} is {@code null}
     */
    public LangfuseTrace tags(String... tags) {
        span.setAttribute(AttributeKey.stringArrayKey(LangfuseAttributes.TRACE_TAGS), Arrays.asList(tags));
        traceState.tags(tags);
        return this;
    }

    /**
     * Records input directly, without applying the automatic capture policy.
     *
     * @param input input value
     * @return this trace
     */
    public LangfuseTrace input(Object input) {
        String value = String.valueOf(input);
        span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, value);
        span.setAttribute(LangfuseAttributes.TRACE_INPUT, value);
        return this;
    }

    /**
     * Records output directly, without applying the automatic capture policy.
     *
     * @param output output value
     * @return this trace
     */
    public LangfuseTrace output(Object output) {
        String value = String.valueOf(output);
        span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, value);
        span.setAttribute(LangfuseAttributes.TRACE_OUTPUT, value);
        return this;
    }

    /**
     * Records trace metadata.
     *
     * @param key metadata key
     * @param value metadata value
     * @return this trace
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public LangfuseTrace metadata(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        span.setAttribute(LangfuseAttributes.TRACE_METADATA + "." + key, value);
        traceState.metadata(key, value);
        return this;
    }

    /**
     * Records the trace version on this trace and subsequently created observations.
     *
     * @param version version identifier
     * @return this trace
     * @throws NullPointerException if {@code version} is {@code null}
     */
    public LangfuseTrace version(String version) {
        Objects.requireNonNull(version, "version");
        span.setAttribute(LangfuseAttributes.VERSION, version);
        traceState.version(version);
        return this;
    }

    /**
     * Records the release on this trace and subsequently created observations.
     *
     * @param release release identifier
     * @return this trace
     * @throws NullPointerException if {@code release} is {@code null}
     */
    public LangfuseTrace release(String release) {
        Objects.requireNonNull(release, "release");
        span.setAttribute(LangfuseAttributes.RELEASE, release);
        traceState.release(release);
        return this;
    }

    /**
     * Records the environment on this trace and subsequently created observations.
     *
     * @param environment environment name
     * @return this trace
     * @throws NullPointerException if {@code environment} is {@code null}
     */
    public LangfuseTrace environment(String environment) {
        Objects.requireNonNull(environment, "environment");
        span.setAttribute(LangfuseAttributes.ENVIRONMENT, environment);
        traceState.environment(environment);
        return this;
    }

    /**
     * Starts a generation using the current OpenTelemetry context as its parent and makes it
     * current.
     *
     * @param name generation name
     * @return the generation; the caller must close it
     */
    public LangfuseGeneration generation(String name) {
        return new LangfuseGeneration(tracer, name);
    }

    /**
     * Runs an action in a generation parented by the current OpenTelemetry context.
     *
     * @param name generation name
     * @param action action to run
     */
    public void generation(String name, Consumer<LangfuseGeneration> action) {
        try (LangfuseGeneration gen = new LangfuseGeneration(tracer, name)) {
            try {
                action.accept(gen);
            } catch (Exception e) {
                gen.recordException(e);
                throw e;
            }
        }
    }

    /**
     * Starts a span using the current OpenTelemetry context as its parent and makes it current.
     *
     * @param name span name
     * @return the span; the caller must close it
     */
    public LangfuseSpan span(String name) {
        return new LangfuseSpan(tracer, name);
    }

    /**
     * Runs an action in a span parented by the current OpenTelemetry context.
     *
     * @param name span name
     * @param action action to run
     */
    public void span(String name, Consumer<LangfuseSpan> action) {
        try (LangfuseSpan s = new LangfuseSpan(tracer, name)) {
            try {
                action.accept(s);
            } catch (Exception e) {
                s.recordException(e);
                throw e;
            }
        }
    }
}
