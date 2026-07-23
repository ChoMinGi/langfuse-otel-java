package io.github.chomingi.langfuse.otel;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.chomingi.langfuse.otel.LangfuseOtel.OpenTelemetryOwnership.EXTERNAL;
import static io.github.chomingi.langfuse.otel.LangfuseOtel.OpenTelemetryOwnership.NONE;
import static io.github.chomingi.langfuse.otel.LangfuseOtel.OpenTelemetryOwnership.OWNED;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.ExportState.FAILED;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.ExportState.NOT_MANAGED;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.ExportState.SUCCEEDED;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.FlushState.NOT_REQUESTED;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.FlushState.TIMED_OUT;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.NoopReason.INITIALIZATION_FAILURE;
import static io.github.chomingi.langfuse.otel.LangfuseOtelStatus.NoopReason.MISSING_CREDENTIALS;
import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelStatusTest {

    @Test
    void noopAndExternalModesDoNotInventOwnedPipelineSignals() {
        try (LangfuseOtel noop = LangfuseOtel.builder().build();
             LangfuseOtel external = LangfuseOtel.externalBuilder(OpenTelemetry.noop()).build()) {
            LangfuseOtelStatus noopStatus = noop.getStatus();
            assertThat(noopStatus.getOpenTelemetryOwnership()).isEqualTo(NONE);
            assertThat(noopStatus.isNoopFallback()).isTrue();
            assertThat(noopStatus.getNoopReason()).isEqualTo(MISSING_CREDENTIALS);
            assertThat(noopStatus.isOperationalSignalsAvailable()).isFalse();
            assertThat(noopStatus.getExportState()).isEqualTo(NOT_MANAGED);
            assertThat(noopStatus.getFlushState())
                    .isEqualTo(LangfuseOtelStatus.FlushState.NOT_MANAGED);

            LangfuseOtelStatus externalStatus = external.getStatus();
            assertThat(externalStatus.getOpenTelemetryOwnership()).isEqualTo(EXTERNAL);
            assertThat(externalStatus.isNoopFallback()).isFalse();
            assertThat(externalStatus.isOperationalSignalsAvailable()).isFalse();
            assertThat(externalStatus.getExportState()).isEqualTo(NOT_MANAGED);
            assertThat(externalStatus.getFlushState())
                    .isEqualTo(LangfuseOtelStatus.FlushState.NOT_MANAGED);
        }
    }

    @Test
    void initializationFailureUsesBoundedNoopReason() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .host("http://example.com")
                .allowInsecureHttpForDevelopment(true)
                .build()) {
            assertThat(langfuse.getStatus().getNoopReason()).isEqualTo(INITIALIZATION_FAILURE);
        }
    }

    @Test
    void successfulStandaloneExportAndFlushUpdateIndependentStates() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        HttpServer receiver = startReceiver(200, requestReceived);
        try {
            try (LangfuseOtel langfuse = standalone(receiver)) {
                assertThat(langfuse.getStatus().getFlushState()).isEqualTo(NOT_REQUESTED);

                langfuse.trace("successful-status-export", trace -> {});
                langfuse.flush();

                assertThat(requestReceived.await(5, TimeUnit.SECONDS)).isTrue();
                LangfuseOtelStatus status = langfuse.getStatus();
                assertThat(status.getOpenTelemetryOwnership()).isEqualTo(OWNED);
                assertThat(status.isOperationalSignalsAvailable()).isTrue();
                assertThat(status.getExportState()).isEqualTo(SUCCEEDED);
                assertThat(status.getFailedExportSpanCount()).isZero();
                assertThat(status.getQueueDroppedSpanCount()).isZero();
                assertThat(status.getFlushState())
                        .isEqualTo(LangfuseOtelStatus.FlushState.SUCCEEDED);
            }
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void exporterFailureIsVisibleEvenWhenBatchProcessorFlushCompletes() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        HttpServer receiver = startReceiver(401, requestReceived);
        try {
            try (LangfuseOtel langfuse = standalone(receiver)) {
                langfuse.trace("failed-status-export", trace -> {});
                langfuse.flush();

                assertThat(requestReceived.await(5, TimeUnit.SECONDS)).isTrue();
                LangfuseOtelStatus status = langfuse.getStatus();
                assertThat(status.getExportState()).isEqualTo(FAILED);
                assertThat(status.getFailedExportSpanCount()).isEqualTo(1);

                // OTel 1.44.1 reports queue drain completion independently of exporter delivery.
                assertThat(status.getFlushState())
                        .isEqualTo(LangfuseOtelStatus.FlushState.SUCCEEDED);
            }
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void flushResultClassificationCoversSuccessFailureAndTimeout() {
        assertThat(LangfuseOtel.awaitFlush(
                CompletableResultCode.ofSuccess(), 1, TimeUnit.MILLISECONDS))
                .isEqualTo(LangfuseOtelStatus.FlushState.SUCCEEDED);
        assertThat(LangfuseOtel.awaitFlush(
                CompletableResultCode.ofFailure(), 1, TimeUnit.MILLISECONDS))
                .isEqualTo(LangfuseOtelStatus.FlushState.FAILED);
        assertThat(LangfuseOtel.awaitFlush(
                new CompletableResultCode(), 1, TimeUnit.MILLISECONDS))
                .isEqualTo(TIMED_OUT);
    }

    @Test
    void interruptedFlushWaitIsFailedRatherThanTimedOutAndPreservesInterrupt() throws Exception {
        AtomicReference<LangfuseOtelStatus.FlushState> state = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            state.set(LangfuseOtel.awaitFlush(
                    new CompletableResultCode(), 1, TimeUnit.SECONDS));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        thread.start();
        thread.join(5_000);

        assertThat(thread.isAlive()).isFalse();
        assertThat(state.get()).isEqualTo(LangfuseOtelStatus.FlushState.FAILED);
        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void olderConcurrentFlushCannotOverwriteNewerOutcome() {
        LangfuseOtelRuntime runtime = LangfuseOtelRuntime.unmonitored(
                true, LangfuseOtelStatus.NoopReason.NONE);
        long first = runtime.beginFlush();
        long second = runtime.beginFlush();

        runtime.completeFlush(second, LangfuseOtelStatus.FlushState.SUCCEEDED);
        assertThat(runtime.snapshot(OWNED, false).getFlushState())
                .isEqualTo(LangfuseOtelStatus.FlushState.IN_PROGRESS);

        runtime.completeFlush(first, LangfuseOtelStatus.FlushState.FAILED);
        LangfuseOtelStatus status = runtime.snapshot(OWNED, false);
        assertThat(status.getFlushState()).isEqualTo(LangfuseOtelStatus.FlushState.SUCCEEDED);
        assertThat(status.getFailedFlushCount()).isEqualTo(1);
    }

    @Test
    void successfulExportRecoversCurrentFailureAndDropStateWithoutResettingCounters() {
        LangfuseOtelRuntime runtime = LangfuseOtelRuntime.managed();
        runtime.recordExportCompleted(false, 3);
        runtime.recordQueueDropped(2);

        LangfuseOtelStatus failed = runtime.snapshot(OWNED, false);
        assertThat(failed.getExportState()).isEqualTo(FAILED);
        assertThat(failed.getFailedExportSpanCount()).isEqualTo(3);
        assertThat(failed.getQueueDroppedSpanCount()).isEqualTo(2);
        assertThat(failed.hasQueueDropsSinceLastSuccessfulExport()).isTrue();

        runtime.recordExportCompleted(true, 1);

        LangfuseOtelStatus recovered = runtime.snapshot(OWNED, false);
        assertThat(recovered.getExportState()).isEqualTo(SUCCEEDED);
        assertThat(recovered.getFailedExportSpanCount()).isEqualTo(3);
        assertThat(recovered.getQueueDroppedSpanCount()).isEqualTo(2);
        assertThat(recovered.hasQueueDropsSinceLastSuccessfulExport()).isFalse();
    }

    private static LangfuseOtel standalone(HttpServer receiver) {
        return LangfuseOtel.builder()
                .publicKey("pk-status")
                .secretKey("sk-status")
                .host("http://127.0.0.1:" + receiver.getAddress().getPort())
                .allowInsecureHttpForDevelopment(true)
                .build();
    }

    private static HttpServer startReceiver(int responseStatus, CountDownLatch received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(responseStatus, 0);
                exchange.getResponseBody().close();
            } finally {
                exchange.close();
                received.countDown();
            }
        });
        server.start();
        return server;
    }
}
