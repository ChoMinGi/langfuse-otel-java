package com.example;

import io.github.chomingi.langfuse.otel.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationConsumerTest {
    final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    final SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    final LangfuseOtel lf = LangfuseOtel.externalBuilder(sdk).build();
    @AfterEach void close() { lf.close(); sdk.close(); }

    @Test void synchronousPublicApiPreservesResultExceptionAndParentMetadataWithoutAProcessor() {
        LangfuseTraceContext metadata = LangfuseTraceContext.builder().userId("user").traceName("workflow").build();
        try (LangfuseObservation root = lf.observation("workflow", ObservationType.CHAIN).traceContext(metadata).start()) {
            Context before = Context.current();
            assertEquals("answer", ObservationExample.sync(lf, root.context(), "private", () -> "answer"));
            assertSame(before, Context.current());
            var child = exporter.getFinishedSpanItems().get(0);
            assertEquals(Span.fromContext(root.context()).getSpanContext().getSpanId(), child.getParentSpanId());
            assertEquals("user", child.getAttributes().get(AttributeKey.stringKey("user.id")));
            assertNull(child.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")));
            RuntimeException failure = new IllegalStateException("business");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> ObservationExample.sync(lf, root.context(), "private", () -> { throw failure; })));
            assertEquals(StatusCode.ERROR, exporter.getFinishedSpanItems().get(1).getStatus().getStatusCode());
        }
    }

    @Test void asynchronousPublicApiPreservesProviderStageAndRestoresSubmissionContext() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        Context before = Context.current();
        assertSame(source, ObservationExample.async(lf, Context.root(), "private", () -> source));
        assertSame(before, Context.current());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { worker.submit(() -> source.complete("answer")).get(5, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); }
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }

    @Test void wrappedCancellationAndSubmissionFailureEndTheirOwnObservations() {
        CompletableFuture<String> source = new CompletableFuture<>();
        ObservationExample.async(lf, Context.root(), "private", () -> source);
        source.completeExceptionally(new CompletionException(new CancellationException()));
        assertEquals("cancelled", exporter.getFinishedSpanItems().get(0).getAttributes()
                .get(AttributeKey.stringKey("langfuse.observation.status_message")));
        RuntimeException failure = new IllegalStateException("submission");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ObservationExample.async(lf, Context.root(), "private", () -> { throw failure; })));
        assertEquals(2, exporter.getFinishedSpanItems().size());
    }

    @Test void externalFlushAndCloseLeaveTheApplicationSdkUsable() {
        lf.observation("before", ObservationType.EVENT).start().end();
        lf.flush(); lf.close();
        Span app = sdk.getTracer("application").spanBuilder("after").startSpan(); app.end();
        assertEquals(2, exporter.getFinishedSpanItems().size());
    }

    @Test void newApiRespectsExternalSampler() {
        try (SdkTracerProvider unsampled = SdkTracerProvider.builder().setSampler(Sampler.alwaysOff())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             LangfuseOtel integration = LangfuseOtel.externalBuilder(OpenTelemetrySdk.builder().setTracerProvider(unsampled).build()).build()) {
            integration.observation("unsampled", ObservationType.GENERATION).start().usage(0, 0).end();
            assertTrue(exporter.getFinishedSpanItems().isEmpty());
        }
    }
}
