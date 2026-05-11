package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Root span of a Langfuse trace. Contains child spans and generations.
 * Automatically inherits userId, sessionId, tags from {@link LangfuseContext}.
 */
public class LangfuseTrace extends AbstractLangfuseSpan {

    private final Tracer tracer;

    LangfuseTrace(Tracer tracer, String name) {
        super(tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(LangfuseAttributes.TRACE_NAME, name)
                .startSpan(), name);
        this.tracer = tracer;
        applyContext();
    }

    private void applyContext() {
        String userId = LangfuseContext.getUserId();
        if (userId != null) span.setAttribute(LangfuseAttributes.TRACE_USER_ID, userId);

        String sessionId = LangfuseContext.getSessionId();
        if (sessionId != null) span.setAttribute(LangfuseAttributes.TRACE_SESSION_ID, sessionId);

        List<String> tags = LangfuseContext.getTags();
        if (!tags.isEmpty()) span.setAttribute(AttributeKey.stringArrayKey(LangfuseAttributes.TRACE_TAGS), tags);

        String environment = LangfuseContext.getEnvironment();
        if (environment != null) span.setAttribute(LangfuseAttributes.ENVIRONMENT, environment);
    }

    public LangfuseTrace userId(String userId) {
        span.setAttribute(LangfuseAttributes.TRACE_USER_ID, userId);
        return this;
    }

    public LangfuseTrace sessionId(String sessionId) {
        span.setAttribute(LangfuseAttributes.TRACE_SESSION_ID, sessionId);
        return this;
    }

    public LangfuseTrace tags(String... tags) {
        span.setAttribute(AttributeKey.stringArrayKey(LangfuseAttributes.TRACE_TAGS), Arrays.asList(tags));
        return this;
    }

    public LangfuseTrace input(Object input) {
        span.setAttribute(LangfuseAttributes.TRACE_INPUT, String.valueOf(input));
        return this;
    }

    public LangfuseTrace output(Object output) {
        span.setAttribute(LangfuseAttributes.TRACE_OUTPUT, String.valueOf(output));
        return this;
    }

    public LangfuseTrace metadata(String key, String value) {
        span.setAttribute(LangfuseAttributes.TRACE_METADATA + "." + key, value);
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
