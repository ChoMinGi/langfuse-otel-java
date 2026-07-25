package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import java.util.function.Consumer;

/**
 * A generic span within a trace. Use for non-LLM steps (preprocessing, postprocessing, tool calls).
 * Can contain nested spans and generations.
 *
 * <p>The span is a synchronous, thread-bound scope. Close it on the thread where it was
 * created, after all child spans and generations have been closed.</p>
 */
public class LangfuseSpan extends AbstractLangfuseSpan {

    private final Tracer tracer;

    LangfuseSpan(Tracer tracer, String name) {
        super(tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "span")
                .startSpan(), name);
        this.tracer = tracer;
    }

    /**
     * Records input directly, without applying the automatic capture policy.
     *
     * @param input input value
     * @return this span
     */
    public LangfuseSpan input(Object input) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(input));
        return this;
    }

    /**
     * Records output directly, without applying the automatic capture policy.
     *
     * @param output output value
     * @return this span
     */
    public LangfuseSpan output(Object output) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, String.valueOf(output));
        return this;
    }

    /**
     * Records observation metadata.
     *
     * @param key metadata key
     * @param value metadata value
     * @return this span
     */
    public LangfuseSpan metadata(String key, String value) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_METADATA + "." + key, value);
        return this;
    }

    /**
     * Records the observation level.
     *
     * @param level level value
     * @return this span
     */
    public LangfuseSpan level(String level) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, level);
        return this;
    }

    /**
     * Records the observation status message.
     *
     * @param message status message
     * @return this span
     */
    public LangfuseSpan statusMessage(String message) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
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
