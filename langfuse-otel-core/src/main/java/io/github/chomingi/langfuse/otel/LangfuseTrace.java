package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.function.Consumer;

public class LangfuseTrace implements AutoCloseable {

    private final Tracer tracer;
    private final Span span;
    private final Scope scope;
    private final java.lang.ref.Cleaner.Cleanable cleanable;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

    LangfuseTrace(Tracer tracer, String name) {
        this.tracer = tracer;
        this.span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(LangfuseAttributes.TRACE_NAME, name)
                .startSpan();
        this.scope = span.makeCurrent();
        this.cleanable = SpanGuard.register(this, span, scope, name);
        applyContext();
    }

    private void applyContext() {
        String userId = LangfuseContext.getUserId();
        if (userId != null) span.setAttribute(LangfuseAttributes.TRACE_USER_ID, userId);

        String sessionId = LangfuseContext.getSessionId();
        if (sessionId != null) span.setAttribute(LangfuseAttributes.TRACE_SESSION_ID, sessionId);

        java.util.List<String> tags = LangfuseContext.getTags();
        if (!tags.isEmpty()) span.setAttribute(LangfuseAttributes.TRACE_TAGS, String.join(",", tags));

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
        span.setAttribute(LangfuseAttributes.TRACE_TAGS, String.join(",", tags));
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

    public void recordException(Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(t);
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
    }

    public Span getSpan() {
        return span;
    }

    public void end() {
        close();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanable.clean();
        }
    }
}
