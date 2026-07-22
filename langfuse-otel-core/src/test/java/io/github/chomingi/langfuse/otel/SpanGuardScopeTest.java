package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.ref.Cleaner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void cleanerPathEndsSpanWithoutOwningThreadAffineScope() {
        Tracer tracer = otel.getOpenTelemetry().getTracer("cleaner-thread-safety-test");
        Span span = tracer.spanBuilder("abandoned-generation").startSpan();
        Object owner = new Object();

        Cleaner.Cleanable cleanable = SpanGuard.register(
                owner, span, "abandoned-generation", new AtomicBoolean(false));
        cleanable.clean();

        assertThat(span.isRecording()).isFalse();
    }

    @Test
    void crossThreadEndFailsWithoutChangingEitherThreadContext() throws Exception {
        Tracer tracer = otel.getOpenTelemetry().getTracer("cross-thread-close-test");
        Span parent = tracer.spanBuilder("parent").startSpan();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Scope parentScope = parent.makeCurrent()) {
            LangfuseGeneration generation = new LangfuseGeneration(tracer, "generation");
            AtomicReference<SpanContext> workerContextBefore = new AtomicReference<>();
            AtomicReference<SpanContext> workerContextAfter = new AtomicReference<>();

            try {
                Future<Throwable> result = executor.submit(() -> {
                    workerContextBefore.set(Span.current().getSpanContext());
                    try {
                        generation.end();
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    } finally {
                        workerContextAfter.set(Span.current().getSpanContext());
                    }
                });

                assertThat(result.get())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Langfuse spans must be closed on the thread where they were created");
                assertThat(workerContextBefore).hasValue(SpanContext.getInvalid());
                assertThat(workerContextAfter).hasValue(SpanContext.getInvalid());
                assertThat(generation.getSpan().isRecording()).isTrue();
                assertThat(Span.current().getSpanContext())
                        .isEqualTo(generation.getSpan().getSpanContext());
            } finally {
                generation.close();
            }

            assertThat(Span.current().getSpanContext()).isEqualTo(parent.getSpanContext());
        } finally {
            executor.shutdownNow();
            parent.end();
        }

        assertThat(otel.getSpans())
                .filteredOn(span -> span.getName().equals("generation"))
                .hasSize(1);
    }

    @Test
    void outOfOrderCloseFailsAndCanBeRetriedInLifoOrder() {
        Tracer tracer = otel.getOpenTelemetry().getTracer("lifo-close-test");
        LangfuseSpan parent = new LangfuseSpan(tracer, "parent");
        LangfuseGeneration child = new LangfuseGeneration(tracer, "child");

        try {
            assertThatThrownBy(parent::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Langfuse spans must be closed in reverse creation order");
            assertThat(parent.getSpan().isRecording()).isTrue();
            assertThat(child.getSpan().isRecording()).isTrue();
            assertThat(Span.current().getSpanContext())
                    .isEqualTo(child.getSpan().getSpanContext());

            child.close();
            assertThat(Span.current().getSpanContext())
                    .isEqualTo(parent.getSpan().getSpanContext());
        } finally {
            child.close();
            parent.close();
        }

        assertThat(Span.current().getSpanContext()).isEqualTo(SpanContext.getInvalid());
        assertThat(otel.getSpans())
                .filteredOn(span -> span.getName().equals("parent") || span.getName().equals("child"))
                .hasSize(2);
    }
}
