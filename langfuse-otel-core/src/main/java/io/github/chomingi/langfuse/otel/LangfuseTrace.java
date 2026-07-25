package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import java.util.Arrays;
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

    LangfuseTrace(Tracer tracer, String name) {
        super(tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(LangfuseAttributes.TRACE_NAME, name)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "span")
                .startSpan(), name);
        this.tracer = tracer;
    }

    /**
     * Records the trace user identifier.
     *
     * @param userId user identifier
     * @return this trace
     */
    public LangfuseTrace userId(String userId) {
        span.setAttribute(LangfuseAttributes.TRACE_USER_ID, userId);
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
     */
    public LangfuseTrace metadata(String key, String value) {
        span.setAttribute(LangfuseAttributes.TRACE_METADATA + "." + key, value);
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
