package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

final class SpanGuard {

    private static final Logger log = LoggerFactory.getLogger(SpanGuard.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private SpanGuard() {}

    static Cleaner.Cleanable register(Object owner, Span span, Scope scope, String spanName, AtomicBoolean closed) {
        return CLEANER.register(owner, new CleanAction(span, scope, spanName, closed));
    }

    private static class CleanAction implements Runnable {
        private final Span span;
        private final Scope scope;
        private final String spanName;
        private final AtomicBoolean closed;

        CleanAction(Span span, Scope scope, String spanName, AtomicBoolean closed) {
            this.span = span;
            this.scope = scope;
            this.spanName = spanName;
            this.closed = closed;
        }

        @Override
        public void run() {
            boolean explicitlyClosed = closed.get();
            if (!explicitlyClosed) {
                log.warn("Langfuse span '{}' was not closed. Ending the span, but its originating thread Scope "
                         + "cannot be restored by the Cleaner. Use try-with-resources, callback API, or call "
                         + "end() explicitly.", spanName);
            }
            try {
                // OTel Scope is thread-affine. Cleaners run on a different thread and therefore
                // cannot restore the originating thread's Context safely.
                if (explicitlyClosed) {
                    scope.close();
                }
            } finally {
                span.end();
            }
        }
    }
}
