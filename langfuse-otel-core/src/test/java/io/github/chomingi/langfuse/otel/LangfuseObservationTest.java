package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.chomingi.langfuse.otel.LangfuseAttributes.*;

class LangfuseObservationTest {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(new LangfuseContextSpanProcessor())
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    private LangfuseOtel lf = LangfuseOtel.externalBuilder(sdk).build();

    @AfterEach void cleanup() { lf.close(); sdk.close(); LangfuseContext.clear(); }
    private LangfuseObservation start() { return lf.observation("test", ObservationType.GENERATION).start(); }
    private SpanData last() { List<SpanData> rows = exporter.getFinishedSpanItems(); return rows.get(rows.size() - 1); }
    private static String attr(SpanData span, String name) { return span.getAttributes().get(AttributeKey.stringKey(name)); }

    @Test void startAndCrossThreadEndDoNotOwnTheCallersScope() throws Exception {
        Context before = Context.current();
        LangfuseObservation root = start();
        assertSame(before, Context.current());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Scope scope = root.makeCurrent()) {
            Context current = Context.current();
            worker.submit(root::end).get(5, TimeUnit.SECONDS);
            assertSame(current, Context.current());
            try (LangfuseObservation child = lf.observation("child-after-end", ObservationType.TOOL).start()) {
                assertEquals(Span.fromContext(root.context()).getSpanContext().getTraceId(),
                        Span.fromContext(child.context()).getSpanContext().getTraceId());
            }
        } finally { worker.shutdownNow(); root.close(); }
        assertSame(before, Context.current());
        assertEquals(2, exporter.getFinishedSpanItems().size());
        assertEquals(Span.fromContext(root.context()).getSpanContext().getSpanId(), last().getParentSpanId());
    }

    @Test void builderResolvesImplicitParentAtStartAndParentEndDoesNotEndChild() {
        LangfuseObservation.Builder builder = lf.observation("child", ObservationType.TOOL);
        LangfuseObservation parent = start();
        LangfuseObservation child;
        try (Scope scope = parent.makeCurrent()) { child = builder.start(); }
        parent.end();
        assertEquals(1, exporter.getFinishedSpanItems().size());
        child.end();
        assertEquals(Span.fromContext(parent.context()).getSpanContext().getSpanId(), last().getParentSpanId());
    }

    @Test void snapshotsOverrideLegacyStateBeforeProcessorWithoutChangingParentOrSibling() throws Exception {
        try (LangfuseTrace legacy = lf.trace("legacy").userId("old").metadata("stale", "old")) {
            ContextKey<String> applicationKey = ContextKey.named("application");
            Context parent = Context.current().with(applicationKey, "kept");
            LangfuseObservation inherited = lf.observation("inherited", ObservationType.CHAIN).parent(parent).start();
            LangfuseObservation replaced = lf.observation("replaced", ObservationType.AGENT).parent(parent)
                    .traceContext(LangfuseTraceContext.builder().userId("new").metadata("run", "new").build()).start();
            legacy.userId("later");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> {
                    LangfuseContext.setUserId("worker");
                    try (Scope scope = replaced.makeCurrent()) {
                        assertEquals("kept", Context.current().get(applicationKey));
                        LangfuseContext.setUserId("ignored");
                        try (LangfuseObservation child = start()) {
                            assertEquals("new", LangfuseContext.from(child.context()).getUserId());
                        }
                    }
                    assertEquals("worker", LangfuseContext.getUserId());
                    LangfuseContext.clear();
                }).get(5, TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
            assertEquals("new", attr(last(), TRACE_USER_ID));
            assertNull(attr(last(), TRACE_METADATA + ".stale"));
            inherited.end(); assertEquals("old", attr(last(), TRACE_USER_ID));
            replaced.end(); assertEquals("new", attr(last(), TRACE_USER_ID));
            assertNull(attr(last(), TRACE_METADATA + ".stale"));
            assertEquals("later", LangfuseContext.current().getUserId());
        }
    }

    @Test void explicitParentAndEmptySnapshotDoNotMergeWorkerLegacyValues() {
        LangfuseContext.setUserId("unrelated");
        Context parent = LangfuseContext.storeIn(Context.root(), LangfuseTraceContext.builder().userId("parent").build());
        try (LangfuseObservation observation = lf.observation("explicit", ObservationType.SPAN).parent(parent).start()) {
            assertEquals("parent", LangfuseContext.from(observation.context()).getUserId());
        }
        try (LangfuseObservation observation = lf.observation("empty", ObservationType.SPAN).parent(parent)
                .traceContext(LangfuseTraceContext.builder().build()).start()) {
            assertNull(LangfuseContext.from(observation.context()).getUserId());
        }
        assertNull(attr(last(), TRACE_USER_ID));
    }

    @Test void legacyChildrenInheritSnapshotAndLegacyInputStillBypassesPolicy() {
        LangfuseTraceContext snapshot = LangfuseTraceContext.builder().userId("new").build();
        try (LangfuseObservation parent = lf.observation("new", ObservationType.CHAIN).traceContext(snapshot).start();
             Scope scope = parent.makeCurrent();
             LangfuseTrace legacy = lf.trace("legacy")) {
            legacy.input("explicit legacy content");
            parent.input("new path defaults to metadata only");
            assertEquals("new", LangfuseContext.current().getUserId());
        }
        assertEquals("explicit legacy content", attr(exporter.getFinishedSpanItems().get(0), OBSERVATION_INPUT));
        assertNull(attr(last(), OBSERVATION_INPUT));
    }

    @ParameterizedTest @EnumSource(ObservationType.class)
    void typesAndCommonAttributesAreRecorded(ObservationType type) {
        LangfuseTraceContext snapshot = LangfuseTraceContext.builder().userId("user").sessionId("session")
                .tags("tag").traceName("workflow").version("revision").release("release")
                .environment("test").metadata("common", "value").build();
        try (LangfuseObservation observation = lf.observation("operation", type).traceContext(snapshot).start()) {
            observation.metadata("local", "value").model("model").usage(0, 7);
        }
        assertEquals(type.name().toLowerCase(java.util.Locale.ROOT), attr(last(), OBSERVATION_TYPE));
        assertEquals("value", attr(last(), TRACE_METADATA + ".common"));
        assertEquals("value", attr(last(), OBSERVATION_METADATA + ".local"));
        assertEquals("model", attr(last(), OBSERVATION_MODEL));
        assertEquals("model", attr(last(), GEN_AI_REQUEST_MODEL));
        assertEquals(0L, last().getAttributes().get(AttributeKey.longKey(GEN_AI_USAGE_INPUT_TOKENS)));
        assertEquals(7L, last().getAttributes().get(AttributeKey.longKey(GEN_AI_USAGE_TOTAL_TOKENS)));
    }

    @Test void validationDoesNotCreateSpansOrSilentlyCoerceUsage() {
        assertThrows(IllegalArgumentException.class, () -> lf.observation(" \t", ObservationType.SPAN));
        assertThrows(NullPointerException.class, () -> lf.observation(null, ObservationType.SPAN));
        assertThrows(NullPointerException.class, () -> lf.observation("name", null));
        assertThrows(NullPointerException.class, () -> lf.observation("name", ObservationType.SPAN).parent(null));
        assertThrows(NullPointerException.class, () -> lf.observation("name", ObservationType.SPAN).traceContext(null));
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        try (LangfuseObservation observation = start()) {
            assertThrows(IllegalArgumentException.class, () -> observation.usage(-1, 0));
            assertThrows(IllegalArgumentException.class, () -> observation.usage(0, -1));
            assertThrows(IllegalArgumentException.class, () -> observation.usage(Long.MAX_VALUE, 1));
            assertThrows(IllegalArgumentException.class, () -> observation.model(" "));
            assertThrows(IllegalArgumentException.class, () -> observation.metadata(" ", "value"));
            assertThrows(NullPointerException.class, () -> observation.metadata("key", null));
            assertThrows(NullPointerException.class, () -> observation.fail(null));
        }
        assertNull(last().getAttributes().get(AttributeKey.longKey(GEN_AI_USAGE_INPUT_TOKENS)));
    }

    @Test void bodyCaptureUsesPolicyAndSkipsUserCodeWhenDisabledOrEnded() {
        AtomicInteger redactions = new AtomicInteger();
        lf = LangfuseOtel.externalBuilder(sdk).contentCapturePolicy(ContentCapturePolicy.builder()
                .captureOutput(true).maxLength(3).redactor((type, text) -> {
                    redactions.incrementAndGet(); return "ab\uD83D\uDE00x";
                }).build()).build();
        LangfuseObservation observation = start();
        observation.input("private").output(null).output("private");
        observation.end();
        observation.input("late").output("late").model(null).metadata(null, null).usage(-1, -1);
        observation.fail(null); observation.cancel(); observation.close();
        assertEquals(1, redactions.get());
        assertNull(attr(last(), OBSERVATION_INPUT));
        assertEquals("ab", attr(last(), OBSERVATION_OUTPUT));
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }

    @Test void defaultFailureRecordsTypeWithoutInvokingThrowableMessage() {
        RuntimeException failure = new RuntimeException() {
            @Override public String getMessage() { throw new AssertionError("must not read private message"); }
        };
        LangfuseObservation observation = start(); observation.fail(failure); observation.close();
        assertEquals(StatusCode.ERROR, last().getStatus().getStatusCode());
        assertEquals(failure.getClass().getName(), attr(last(), OBSERVATION_STATUS_MESSAGE));
        assertEquals(1, last().getEvents().size());
        assertNull(last().getEvents().get(0).getAttributes().get(AttributeKey.stringKey("exception.message")));
    }

    @Test void exceptionCaptureRedactsMessagesAndRendersStacksWithoutEmbeddedMessages() {
        lf = LangfuseOtel.externalBuilder(sdk).exceptionCapturePolicy(ExceptionCapturePolicy.builder()
                .captureMessage(true).captureStackTrace(true)
                .redactor((type, text) -> type == ExceptionCaptureType.MESSAGE ? "redacted" : text).build()).build();
        IllegalStateException failure = new IllegalStateException("private", new IllegalArgumentException("cause-private"));
        failure.addSuppressed(new RuntimeException("suppressed-private"));
        LangfuseObservation observation = start(); observation.fail(failure);
        assertEquals("redacted", last().getStatus().getDescription());
        var event = last().getEvents().get(0).getAttributes();
        assertEquals("redacted", event.get(AttributeKey.stringKey("exception.message")));
        assertFalse(event.get(AttributeKey.stringKey("exception.stacktrace")).contains("private"));
    }

    @Test void nonfatalRedactorFailuresDropDetailsAndStillTerminate() {
        lf = LangfuseOtel.externalBuilder(sdk).contentCapturePolicy(ContentCapturePolicy.builder().captureInput(true)
                .redactor((type, value) -> { throw new AssertionError("redaction failed"); }).build())
                .exceptionCapturePolicy(ExceptionCapturePolicy.builder().captureMessage(true).captureStackTrace(true)
                        .redactor((type, value) -> { throw new IllegalStateException("redaction failed"); }).build()).build();
        LangfuseObservation observation = start(); observation.input("private");
        observation.fail(new IllegalArgumentException("private"));
        assertNull(attr(last(), OBSERVATION_INPUT));
        assertEquals(1, last().getEvents().get(0).getAttributes().size());
        assertEquals(StatusCode.ERROR, last().getStatus().getStatusCode());
    }

    @Test void fatalRedactorErrorsPropagateAndFailStillEnds() {
        LinkageError fatal = new LinkageError("synthetic");
        lf = LangfuseOtel.externalBuilder(sdk).contentCapturePolicy(ContentCapturePolicy.builder().captureInput(true)
                .redactor((type, value) -> { throw fatal; }).build())
                .exceptionCapturePolicy(ExceptionCapturePolicy.builder().captureMessage(true)
                        .redactor((type, value) -> { throw fatal; }).build()).build();
        LangfuseObservation observation = start();
        assertSame(fatal, assertThrows(LinkageError.class, () -> observation.input("private")));
        assertSame(fatal, assertThrows(LinkageError.class, () -> observation.fail(new RuntimeException("private"))));
        observation.close();
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertEquals(StatusCode.ERROR, last().getStatus().getStatusCode());
    }

    @Test void cancelEndsObservationButNotTheRequestAndIgnoresLateSignals() {
        LangfuseObservation observation = start();
        CompletableFuture<String> request = new CompletableFuture<>();
        request.whenComplete((value, failure) -> { observation.output(value); observation.end(); });
        observation.cancel();
        assertFalse(request.isDone());
        request.complete("late"); observation.fail(new RuntimeException("late"));
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertEquals(StatusCode.UNSET, last().getStatus().getStatusCode());
        assertEquals("cancelled", attr(last(), OBSERVATION_STATUS_MESSAGE));
        assertTrue(last().getEvents().isEmpty());
    }

    @Test void terminalRaceProducesOneCoherentState() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 150; round++) {
                LangfuseObservation observation = start();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> end = workers.submit(() -> { await(start); observation.end(); });
                Future<?> fail = workers.submit(() -> { await(start); observation.fail(new IllegalStateException("private")); });
                Future<?> cancel = workers.submit(() -> { await(start); observation.cancel(); });
                start.countDown();
                end.get(5, TimeUnit.SECONDS); fail.get(5, TimeUnit.SECONDS); cancel.get(5, TimeUnit.SECONDS);
                assertEquals(round + 1, exporter.getFinishedSpanItems().size());
                SpanData result = last();
                if (result.getStatus().getStatusCode() == StatusCode.ERROR) {
                    assertEquals("ERROR", attr(result, OBSERVATION_LEVEL));
                    assertEquals(IllegalStateException.class.getName(), attr(result, OBSERVATION_STATUS_MESSAGE));
                    assertEquals(1, result.getEvents().size());
                } else {
                    assertNull(attr(result, OBSERVATION_LEVEL));
                    assertTrue(result.getEvents().isEmpty());
                    String status = attr(result, OBSERVATION_STATUS_MESSAGE);
                    assertTrue(status == null || status.equals("cancelled"));
                }
            }
        } finally { workers.shutdownNow(); }
    }

    @Test void redactorAndFailurePreparationDoNotBlockCancellationOrWriteAfterIt() throws Exception {
        for (boolean exception : new boolean[]{false, true}) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            lf = LangfuseOtel.externalBuilder(sdk)
                    .contentCapturePolicy(ContentCapturePolicy.builder().captureOutput(true)
                            .redactor((type, value) -> { entered.countDown(); await(release); return "late"; }).build())
                    .exceptionCapturePolicy(ExceptionCapturePolicy.builder().captureMessage(true)
                            .redactor((type, value) -> { entered.countDown(); await(release); return "late"; }).build()).build();
            LangfuseObservation observation = start();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> record = workers.submit(() -> {
                    if (exception) observation.fail(new IllegalStateException("private")); else observation.output("private");
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                workers.submit(observation::cancel).get(5, TimeUnit.SECONDS);
                release.countDown(); record.get(5, TimeUnit.SECONDS);
                assertEquals("cancelled", attr(last(), OBSERVATION_STATUS_MESSAGE));
                assertNull(attr(last(), OBSERVATION_OUTPUT));
                assertTrue(last().getEvents().isEmpty());
            } finally { release.countDown(); workers.shutdownNow(); observation.close(); }
        }
    }

    @Test void externalOnEndProcessorCanWaitForAnotherThreadUsingTheSameObservation() {
        AtomicReference<LangfuseObservation> reference = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean completed = new AtomicBoolean();
        SpanProcessor processor = new SpanProcessor() {
            public void onStart(Context parent, ReadWriteSpan span) {}
            public boolean isStartRequired() { return false; }
            public boolean isEndRequired() { return true; }
            public void onEnd(ReadableSpan span) {
                try {
                    worker.submit(() -> { reference.get().output("late"); reference.get().end(); }).get(5, TimeUnit.SECONDS);
                    completed.set(true);
                } catch (Exception failure) { throw new AssertionError(failure); }
            }
        };
        try (SdkTracerProvider app = SdkTracerProvider.builder().addSpanProcessor(processor).build();
             LangfuseOtel external = LangfuseOtel.externalBuilder(OpenTelemetrySdk.builder().setTracerProvider(app).build()).build()) {
            reference.set(external.observation("reentrant", ObservationType.SPAN).start());
            reference.get().end(); assertTrue(completed.get());
        } finally { worker.shutdownNow(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
