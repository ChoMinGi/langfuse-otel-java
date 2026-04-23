package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import java.util.function.Consumer;

public class LangfuseSpan extends AbstractLangfuseSpan {

    private final Tracer tracer;

    LangfuseSpan(Tracer tracer, String name) {
        super(tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan(), name);
        this.tracer = tracer;
    }

    public LangfuseSpan input(Object input) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(input));
        return this;
    }

    public LangfuseSpan output(Object output) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, String.valueOf(output));
        return this;
    }

    public LangfuseSpan metadata(String key, String value) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_METADATA + "." + key, value);
        return this;
    }

    public LangfuseSpan level(String level) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, level);
        return this;
    }

    public LangfuseSpan statusMessage(String message) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
        return this;
    }

    public LangfuseGeneration generation(String name) {
        return new LangfuseGeneration(tracer, name);
    }

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

    public LangfuseSpan span(String name) {
        return new LangfuseSpan(tracer, name);
    }

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
