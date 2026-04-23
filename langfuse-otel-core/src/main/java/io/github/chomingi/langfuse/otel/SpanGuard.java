package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;

final class SpanGuard {

    private static final Logger log = LoggerFactory.getLogger(SpanGuard.class);
    private static final Cleaner CLEANER = Cleaner.create();

    private SpanGuard() {}

    static Cleaner.Cleanable register(Object owner, Span span, Scope scope, String spanName) {
        return CLEANER.register(owner, new CleanAction(span, scope, spanName));
    }

    private static class CleanAction implements Runnable {
        private final Span span;
        private final Scope scope;
        private final String spanName;

        CleanAction(Span span, Scope scope, String spanName) {
            this.span = span;
            this.scope = scope;
            this.spanName = spanName;
        }

        @Override
        public void run() {
            if (span.isRecording()) {
                log.warn("Langfuse span '{}' was not closed. Auto-closing to prevent leak. "
                         + "Use try-with-resources, callback API, or call end() explicitly.", spanName);
                scope.close();
                span.end();
            }
        }
    }
}
