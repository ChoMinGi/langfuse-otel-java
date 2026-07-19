package io.github.chomingi.langfuse.otel.spring;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.function.Supplier;

/** Makes a wrapper span current only for a synchronous delegate invocation. */
final class SpanScopeSupport {

    private SpanScopeSupport() {}

    static <T> T call(Span span, Supplier<T> invocation) {
        return callWithScope(span::makeCurrent, invocation);
    }

    static <T> T call(Context context, Supplier<T> invocation) {
        return callWithScope(context::makeCurrent, invocation);
    }

    private static <T> T callWithScope(ScopeFactory scopeFactory, Supplier<T> invocation) {
        Scope scope = null;
        try {
            scope = scopeFactory.makeCurrent();
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            // Parent propagation is best-effort and must not block the model call.
        }

        try {
            return invocation.get();
        } finally {
            if (scope != null) {
                try {
                    scope.close();
                } catch (Throwable failure) {
                    InstrumentationFailureSupport.rethrowIfFatal(failure);
                    // Nonfatal instrumentation cleanup must not replace a model result or exception.
                }
            }
        }
    }

    static void run(Span span, Runnable invocation) {
        call(span, () -> {
            invocation.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface ScopeFactory {
        Scope makeCurrent();
    }
}
