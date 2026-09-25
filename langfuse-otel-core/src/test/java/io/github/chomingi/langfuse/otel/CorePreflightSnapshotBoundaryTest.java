package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only feasibility probe. No new production API or resolver precedence is installed. */
class CorePreflightSnapshotBoundaryTest {
    private static Context frozenBoundary(Context parent, LangfuseTraceContext snapshot) {
        LangfuseTraceState carrier = new LangfuseTraceState(snapshot, false);
        carrier.freeze();
        return LangfuseContext.storeTraceState(LangfuseContext.storeIn(parent, snapshot), carrier);
    }

    @Test void frozenCarrierShadowsOldStateBeforeProcessorAndPreservesOtherContext() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new LangfuseContextSpanProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             LangfuseOtel lf = LangfuseOtel.externalBuilder(OpenTelemetrySdk.builder().setTracerProvider(provider).build()).build();
             LangfuseTrace legacy = lf.trace("legacy").userId("old").metadata("old", "must-not-copy")) {
            ContextKey<String> applicationKey = ContextKey.named("preflight-application-key");
            Context old = Context.current().with(applicationKey, "preserved");
            LangfuseTraceContext snapshot = LangfuseTraceContext.builder().userId("new")
                    .traceName("new-path").metadata("run", "preflight").build();
            Context boundary = frozenBoundary(old, snapshot);
            legacy.userId("old-updated");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> {
                    LangfuseContext.setUserId("worker");
                    try (Scope scope = boundary.makeCurrent()) {
                        assertEquals("preserved", Context.current().get(applicationKey));
                        assertEquals("new", LangfuseContext.current().getUserId());
                        LangfuseContext.setUserId("ignored");
                        Span child = lf.getTracer().spanBuilder("new-child").setParent(boundary).startSpan();
                        child.end();
                        assertEquals("new", LangfuseContext.current().getUserId());
                    } finally { LangfuseContext.clear(); }
                }).get(5, TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
            var child = exporter.getFinishedSpanItems().get(0);
            assertEquals(legacy.getSpan().getSpanContext().getSpanId(), child.getParentSpanId());
            assertEquals("new", child.getAttributes().get(AttributeKey.stringKey("user.id")));
            assertNull(child.getAttributes().get(AttributeKey.stringKey("langfuse.trace.metadata.old")));
            assertEquals("old-updated", LangfuseContext.from(old).getUserId());
        }
    }

    @Test void legacyFluentSetterStillWritesItsOwnSpanEvenWhenCarrierIsFrozen() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new LangfuseContextSpanProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             LangfuseOtel lf = LangfuseOtel.externalBuilder(OpenTelemetrySdk.builder().setTracerProvider(provider).build()).build()) {
            Context before = Context.current();
            Context boundary = frozenBoundary(before, LangfuseTraceContext.builder().userId("frozen").build());
            try (Scope scope = boundary.makeCurrent(); LangfuseTrace trace = lf.trace("legacy-child")) {
                trace.userId("ignored");
                assertEquals("frozen", LangfuseContext.current().getUserId());
            }
            assertSame(before, Context.current());
            // The carrier is frozen, but the old fluent setter also writes directly to its span.
            // Therefore frozen-carrier reuse supports inheritance, not unrestricted setter mixing.
            assertEquals("ignored", exporter.getFinishedSpanItems().get(0).getAttributes().get(AttributeKey.stringKey("user.id")));
        }
    }
}
