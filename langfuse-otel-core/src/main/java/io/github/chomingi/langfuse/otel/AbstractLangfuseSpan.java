package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

abstract class AbstractLangfuseSpan implements AutoCloseable {

    protected final Span span;
    private final Scope scope;
    private final java.lang.ref.Cleaner.Cleanable cleanable;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    protected AbstractLangfuseSpan(Span span, String name) {
        this.span = span;
        this.scope = span.makeCurrent();
        this.cleanable = SpanGuard.register(this, span, scope, name);
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
