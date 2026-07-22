package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

abstract class AbstractLangfuseSpan implements AutoCloseable {

    protected final Span span;
    private final Scope scope;
    private final Thread openingThread;
    private final Context installedContext;
    private final java.lang.ref.Cleaner.Cleanable cleanable;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    protected AbstractLangfuseSpan(Span span, String name) {
        this.span = span;
        this.openingThread = Thread.currentThread();
        Scope openedScope = null;
        Context openedContext = null;
        java.lang.ref.Cleaner.Cleanable registeredCleanable;
        try {
            // External OpenTelemetry SDKs do not install our SpanProcessor, so library-created spans
            // also apply the current immutable metadata directly.
            LangfuseContext.applyTo(span, LangfuseContext.current());
            openedScope = span.makeCurrent();
            openedContext = Context.current();
            registeredCleanable = SpanGuard.register(this, span, name, closed);
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
        this.installedContext = openedContext;
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

    @Override
    public void close() {
        if (closed.get()) {
            return;
        }
        if (Thread.currentThread() != openingThread) {
            throw new IllegalStateException(
                    "Langfuse spans must be closed on the thread where they were created");
        }
        // Scope.close() can restore its parent only while this exact attached Context is current.
        if (Context.current() != installedContext) {
            throw new IllegalStateException(
                    "Langfuse spans must be closed in reverse creation order");
        }
        if (closed.compareAndSet(false, true)) {
            try {
                scope.close();
            } finally {
                cleanable.clean();
            }
        }
    }
}
