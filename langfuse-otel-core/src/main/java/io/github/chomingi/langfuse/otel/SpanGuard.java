package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

final class SpanGuard {

    private static final Logger log = LoggerFactory.getLogger(SpanGuard.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private SpanGuard() {}

    static Cleaner.Cleanable register(Object owner, Span span, String spanName, AtomicBoolean closed) {
        return CLEANER.register(owner, new CleanAction(span, spanName, closed));
    }

    private static class CleanAction implements Runnable {
        private final Span span;
        private final String spanName;
        private final AtomicBoolean closed;

        CleanAction(Span span, String spanName, AtomicBoolean closed) {
            this.span = span;
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
            span.end();
        }
    }
}
