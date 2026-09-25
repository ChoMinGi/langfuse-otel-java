package preflight;

import io.github.chomingi.langfuse.otel.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.chomingi.langfuse.otel.LangfuseAttributes.*;

/** Characterizes today's behavior, including gaps. Passing does NOT mean the proposed contract passes. */
class ExistingHelpersTest {
    InMemorySpanExporter exporter;
    SdkTracerProvider provider;
    OpenTelemetrySdk sdk;
    LangfuseOtel lf;
    final LangfuseTraceContext snapshot = LangfuseTraceContext.builder().userId("parent-user")
            .sessionId("session").metadata("run", "preflight").build();

    @BeforeEach void setup() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        lf = LangfuseOtel.externalBuilder(sdk).build();
    }
    @AfterEach void cleanup() { lf.close(); sdk.close(); LangfuseContext.clear(); }
    Span start() { return lf.getTracer().spanBuilder("test").setNoParent().startSpan(); }
    SpanData last() { List<SpanData> rows = exporter.getFinishedSpanItems(); return rows.get(rows.size()-1); }
    String attr(String key) { return last().getAttributes().get(AttributeKey.stringKey(key)); }

    @Test void syncPreservesResultHierarchyAndScope() {
        Span parent = start(); Context context = Context.root().with(parent);
        Context before = Context.current();
        assertEquals("answer", RawOtelExamples.sync(lf, context, snapshot, "private", () -> {
            assertNotEquals(parent.getSpanContext().getSpanId(), Span.current().getSpanContext().getSpanId());
            return "answer";
        }));
        assertSame(before, Context.current());
        assertEquals(parent.getSpanContext().getSpanId(), last().getParentSpanId());
        assertEquals("parent-user", attr(TRACE_USER_ID));
        assertNull(attr(OBSERVATION_INPUT)); assertNull(attr(OBSERVATION_OUTPUT));
        parent.end();
    }
    @Test void syncPreservesOriginalFailureAndRestoresScope() {
        RuntimeException failure = new IllegalArgumentException("synthetic-private-message");
        Context before = Context.current();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
                RawOtelExamples.sync(lf, Context.root(), snapshot, "private", () -> { throw failure; })));
        assertSame(before, Context.current());
        assertEquals(StatusCode.ERROR, last().getStatus().getStatusCode());
        assertFalse(last().getEvents().get(0).getAttributes().asMap().toString().contains("synthetic-private-message"));
    }
    @Test void asyncPreservesStageAndDoesNotHoldScopeAcrossThreads() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        Context before = Context.current();
        assertSame(source, RawOtelExamples.async(lf, Context.root(), snapshot, "private", () -> source));
        assertSame(before, Context.current()); assertTrue(exporter.getFinishedSpanItems().isEmpty());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { worker.submit(() -> source.complete("done")).get(5, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); }
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }
    @Test void wrappedCancellationIsNotAutomaticallyAnError() {
        CompletableFuture<String> source = new CompletableFuture<>();
        RawOtelExamples.async(lf, Context.root(), snapshot, "private", () -> source);
        source.completeExceptionally(new CompletionException(new CancellationException()));
        assertEquals("cancelled", attr(OBSERVATION_STATUS_MESSAGE));
        assertEquals(StatusCode.UNSET, last().getStatus().getStatusCode());
    }
    @Test void asyncSubmissionFailurePreservesOriginalExceptionAndEndsSpan() {
        IllegalStateException failure = new IllegalStateException("submission");
        Context before = Context.current();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> RawOtelExamples.async(
                lf, Context.root(), snapshot, "synthetic", () -> { throw failure; })));
        assertSame(before, Context.current());
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertEquals(StatusCode.ERROR, last().getStatus().getStatusCode());
    }
    @Test void providerInternalRetriesRemainOneLogicalObservation() {
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<String> logicalResult = new CompletableFuture<>();
        RawOtelExamples.async(lf, Context.root(), snapshot, "synthetic", () -> logicalResult);
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        // Simulate a provider that exposes only the aggregate stage, not its failed attempts.
        while (!logicalResult.isDone()) {
            try {
                if (attempts.incrementAndGet() < 3) throw new IllegalStateException("retryable attempt");
                logicalResult.complete("final response");
            } catch (IllegalStateException retryable) {
                assertTrue(exporter.getFinishedSpanItems().isEmpty());
            }
        }
        assertEquals(3, attempts.get());
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertEquals(StatusCode.UNSET, last().getStatus().getStatusCode());
        assertNull(last().getAttributes().get(AttributeKey.longKey(GEN_AI_USAGE_TOTAL_TOKENS)));
    }
    @Test void incompleteFutureRetainsCompletionAndDoesNotExportUntilApplicationCancels() {
        CompletableFuture<String> source = new CompletableFuture<>();
        RawOtelExamples.async(lf, Context.root(), snapshot, "private", () -> source);
        assertEquals(1, source.getNumberOfDependents());
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        source.cancel(false);
        assertEquals(0, source.getNumberOfDependents());
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertFalse(source.complete("late"));
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }
    @Test void endingObservationDoesNotCancelProviderWork() {
        CompletableFuture<String> providerWork = new CompletableFuture<>();
        Span span = start();
        providerWork.whenComplete((r, f) -> { lf.recordOutput(span, r); span.end(); });
        span.setAttribute(OBSERVATION_STATUS_MESSAGE, "cancelled"); span.end();
        assertFalse(providerWork.isDone());
        providerWork.complete("late");
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertEquals("cancelled", attr(OBSERVATION_STATUS_MESSAGE));
    }
    @Test void explicitParentSnapshotIgnoresWorkerThreadLocal() throws Exception {
        Context parent = LangfuseContext.storeIn(Context.root(), snapshot);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                LangfuseContext.setUserId("worker-user");
                try {
                    Span span = RawOtelExamples.start(lf, "child", "tool", parent, LangfuseContext.from(parent));
                    span.end();
                } finally { LangfuseContext.clear(); }
            }).get(5, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
        assertEquals("parent-user", attr(TRACE_USER_ID));
    }
    @Test void immutableSnapshotDoesNotOverrideLegacyMutableCarrier() {
        try (LangfuseTrace trace = lf.trace("legacy").userId("legacy-user")) {
            Context captured = Context.current();
            Context replaced = LangfuseContext.storeIn(captured, snapshot);
            assertEquals("legacy-user", LangfuseContext.from(replaced).getUserId());
            trace.userId("changed-later");
            assertEquals("changed-later", LangfuseContext.from(replaced).getUserId());
            assertEquals("parent-user", snapshot.getUserId());
        }
    }
    @Test void applyingReplacementSnapshotLeavesOmittedMetadataBehind() {
        Span span = start();
        LangfuseContext.applyTo(span, LangfuseTraceContext.builder().metadata("old", "remains").build());
        LangfuseContext.applyTo(span, snapshot); span.end();
        assertEquals("remains", attr(TRACE_METADATA + ".old"));
        assertEquals("preflight", attr(TRACE_METADATA + ".run"));
    }
    @Test void legacyWrapperRequiresSameThreadClose() throws Exception {
        LangfuseTrace trace = lf.trace("legacy");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { worker.submit(() -> assertThrows(IllegalStateException.class, trace::close)).get(5, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); trace.close(); }
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }
    @Test void repeatedEndExportsOnceButDoesNotMakeFailureAndEndAtomic() throws Exception {
        Span span = start();
        CountDownLatch recordedFailure = new CountDownLatch(1), successfulEnd = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> failurePath = worker.submit(() -> {
                lf.recordException(span, new IllegalStateException("failure"));
                recordedFailure.countDown();
                try { assertTrue(successfulEnd.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                span.end();
            });
            assertTrue(recordedFailure.await(5, TimeUnit.SECONDS));
            span.end(); // The successful terminal caller ends first, but the failure attributes already won.
            successfulEnd.countDown(); failurePath.get(5, TimeUnit.SECONDS);
        } finally { successfulEnd.countDown(); worker.shutdownNow(); span.end(); }
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertEquals(StatusCode.ERROR, last().getStatus().getStatusCode());
    }
    @Test void disabledCollectionSkipsConversionAndRedaction() {
        AtomicInteger calls = new AtomicInteger();
        lf = LangfuseOtel.externalBuilder(sdk).contentCapturePolicy(ContentCapturePolicy.builder()
                .redactor((type, text) -> { calls.incrementAndGet(); return text; }).build()).build();
        Span span = start();
        lf.recordInput(span, new Object() { public String toString() { fail("must not convert"); return ""; } });
        span.end(); assertEquals(0, calls.get()); assertNull(attr(OBSERVATION_INPUT));
    }
    @Test void redactorFailureDropsValueButEvenFatalErrorIsCurrentlySwallowed() {
        lf = LangfuseOtel.externalBuilder(sdk).contentCapturePolicy(ContentCapturePolicy.builder()
                .captureInput(true).redactor((type, text) -> { throw new SyntheticVmError(); }).build()).build();
        Span span = start();
        assertDoesNotThrow(() -> lf.recordInput(span, "private"));
        span.end(); assertNull(attr(OBSERVATION_INPUT));
    }
    static class SyntheticVmError extends VirtualMachineError { private static final long serialVersionUID = 1L; }
    @Test void endedSpanStillRunsUserRedactor() {
        AtomicInteger calls = new AtomicInteger();
        lf = LangfuseOtel.externalBuilder(sdk).contentCapturePolicy(ContentCapturePolicy.builder()
                .captureOutput(true).redactor((type, text) -> { calls.incrementAndGet(); return text; }).build()).build();
        Span span = start(); span.end(); lf.recordOutput(span, "late");
        assertEquals(1, calls.get()); assertNull(attr(OBSERVATION_OUTPUT));
    }
    @Test void redactionConcurrentWithEndLosesValueWithoutBlockingEnd() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        lf = LangfuseOtel.externalBuilder(sdk).contentCapturePolicy(ContentCapturePolicy.builder()
                .captureOutput(true).redactor((type, text) -> {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return "redacted";
                }).build()).build();
        Span span = start(); ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> record = worker.submit(() -> lf.recordOutput(span, "private"));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); span.end(); release.countDown();
            record.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); worker.shutdownNow(); span.end(); }
        assertNull(attr(OBSERVATION_OUTPUT));
    }
    @Test void sdkLengthLimitCanCorruptStructuredUsageJson() {
        try (SdkTracerProvider limited = SdkTracerProvider.builder()
                .setSpanLimits(SpanLimits.builder().setMaxAttributeValueLength(8).build())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            Span span = limited.get("preflight").spanBuilder("limited").startSpan();
            span.setAttribute(OBSERVATION_USAGE_DETAILS, "{\"input\":12,\"output\":34}"); span.end();
            assertEquals("{\"input\"", attr(OBSERVATION_USAGE_DETAILS));
        }
    }
    @Test void sdkAttributeCountLimitCanDropRequiredFields() {
        try (SdkTracerProvider limited = SdkTracerProvider.builder()
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(1).build())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            Span span = limited.get("preflight").spanBuilder("limited").startSpan();
            span.setAttribute("unrelated", "first"); span.setAttribute(OBSERVATION_TYPE, "generation"); span.end();
            assertNull(attr(OBSERVATION_TYPE)); assertEquals(2, last().getTotalAttributeCount());
        }
    }
    @Test void externalCloseAndFlushDoNotCloseApplicationSdk() {
        lf.flush(); lf.close();
        Span span = sdk.getTracer("application").spanBuilder("still-open").startSpan(); span.end();
        assertEquals("still-open", last().getName());
    }
    @Test void externalSamplerIsRespected() {
        try (SdkTracerProvider unsampled = SdkTracerProvider.builder().setSampler(Sampler.alwaysOff())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            OpenTelemetrySdk app = OpenTelemetrySdk.builder().setTracerProvider(unsampled).build();
            try (LangfuseOtel external = LangfuseOtel.externalBuilder(app).build()) {
                RawOtelExamples.sync(external, Context.root(), snapshot, "private", () -> "ok");
                assertTrue(exporter.getFinishedSpanItems().isEmpty());
            }
        }
    }
    @Test void applicationCanInstallPublicContextMappingForThirdPartySpans() {
        SpanProcessor copy = new SpanProcessor() {
            public void onStart(Context parent, ReadWriteSpan span) {
                LangfuseContext.applyTo(span, LangfuseContext.from(parent));
            }
            public boolean isStartRequired() { return true; }
            public void onEnd(ReadableSpan span) {}
            public boolean isEndRequired() { return false; }
        };
        try (SdkTracerProvider app = SdkTracerProvider.builder().addSpanProcessor(copy)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            Context parent = LangfuseContext.storeIn(Context.root(), snapshot);
            Span thirdParty = app.get("third-party").spanBuilder("library-outside-langfuse").setParent(parent).startSpan();
            thirdParty.end(); assertEquals("parent-user", attr(TRACE_USER_ID));
        }
    }
    @Test void endedParentStillAllowsChildWithSameTrace() {
        Span parent = start(); Context captured = Context.root().with(parent); parent.end();
        Span child = RawOtelExamples.start(lf, "after-parent", "tool", captured, snapshot); child.end();
        assertEquals(parent.getSpanContext().getTraceId(), last().getTraceId());
        assertEquals(parent.getSpanContext().getSpanId(), last().getParentSpanId());
    }
}
