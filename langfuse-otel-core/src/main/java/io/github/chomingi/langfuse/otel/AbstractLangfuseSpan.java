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
        Scope openedScope = null;
        java.lang.ref.Cleaner.Cleanable registeredCleanable;
        try {
            // External OpenTelemetry SDKs do not install our SpanProcessor, so library-created spans
            // also apply the current immutable metadata directly.
            LangfuseContext.applyTo(span, LangfuseContext.current());
            openedScope = span.makeCurrent();
            registeredCleanable = SpanGuard.register(this, span, openedScope, name, closed);
        } catch (Throwable setupFailure) {
            try {
                if (openedScope != null) {
                    openedScope.close();
                }
            } catch (Throwable ignored) {
                // Preserve the original setup failure.
            } finally {
                try {
                    span.end();
                } catch (Throwable ignored) {
                    // Preserve the original setup failure.
                }
            }
            throw setupFailure;
        }
        this.scope = openedScope;
        this.cleanable = registeredCleanable;
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
