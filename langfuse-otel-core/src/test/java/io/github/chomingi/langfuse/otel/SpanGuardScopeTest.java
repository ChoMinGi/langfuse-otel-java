package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SpanGuardScopeTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void wrapperCloseRestoresParentContextAfterRawSpanWasEndedFirst() {
        Tracer tracer = otel.getOpenTelemetry().getTracer("scope-restoration-test");
        Span parent = tracer.spanBuilder("parent").startSpan();

        try (Scope parentScope = parent.makeCurrent()) {
            LangfuseGeneration generation = new LangfuseGeneration(tracer, "generation");
            assertThat(Span.current().getSpanContext())
                    .isEqualTo(generation.getSpan().getSpanContext());

            generation.getSpan().end();
            assertThat(generation.getSpan().isRecording()).isFalse();

            generation.close();
            assertThat(Span.current().getSpanContext())
                    .isEqualTo(parent.getSpanContext());
        } finally {
            parent.end();
        }

        assertThat(otel.getSpans())
                .filteredOn(span -> span.getName().equals("generation"))
                .hasSize(1);
    }

    @Test
    void cleanerPathEndsSpanWithoutClosingThreadAffineScope() {
        Tracer tracer = otel.getOpenTelemetry().getTracer("cleaner-thread-safety-test");
        Span span = tracer.spanBuilder("abandoned-generation").startSpan();
        AtomicInteger scopeCloseCalls = new AtomicInteger();
        Scope threadAffineScope = scopeCloseCalls::incrementAndGet;
        Object owner = new Object();

        Cleaner.Cleanable cleanable = SpanGuard.register(
                owner, span, threadAffineScope, "abandoned-generation", new AtomicBoolean(false));
        cleanable.clean();

        assertThat(scopeCloseCalls).hasValue(0);
        assertThat(span.isRecording()).isFalse();
    }

    @Test
    void explicitClosePathStillClosesScopeBeforeEndingSpan() {
        Tracer tracer = otel.getOpenTelemetry().getTracer("explicit-close-test");
        Span span = tracer.spanBuilder("explicit-generation").startSpan();
        AtomicInteger scopeCloseCalls = new AtomicInteger();
        Scope scope = scopeCloseCalls::incrementAndGet;
        AtomicBoolean closed = new AtomicBoolean(true);
        Object owner = new Object();

        Cleaner.Cleanable cleanable = SpanGuard.register(owner, span, scope, "explicit-generation", closed);
        cleanable.clean();

        assertThat(scopeCloseCalls).hasValue(1);
        assertThat(span.isRecording()).isFalse();
    }
}
