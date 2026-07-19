package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
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
        this.cleanable = SpanGuard.register(this, span, scope, name, closed);
    }

    public void recordException(Throwable t) {
        ExceptionRecorder.recordTypeOnly(span, t);
    }

    public Span getSpan() {
        return span;
    }

    public void end() {
        close();
    }

    // Delegates entirely to cleanable.clean() — see DESIGN.md #6 for why we don't call scope.close()/span.end() here
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanable.clean();
        }
    }
}
