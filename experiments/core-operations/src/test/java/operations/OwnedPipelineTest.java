package operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chomingi.langfuse.otel.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 90)
class OwnedPipelineTest {
    @Test void boundedQueueDoesNotBlockProducerAndRecovers() throws Exception {
        try (LocalOtlpReceiver receiver = new LocalOtlpReceiver(true);
             LangfuseOtel client = receiver.client(false)) {
            receiver.mode = LocalOtlpReceiver.Mode.BLOCK;
            emit(client, 512);
            assertTrue(receiver.entered.await(8, TimeUnit.SECONDS));
            long start = System.nanoTime();
            emit(client, 4096);
            double producerMs = elapsedMs(start);
            assertTrue(producerMs < 5000, "producer unexpectedly waited for blocked network");
            assertEquals(2048, client.getStatus().getQueueDroppedSpanCount());
            assertTrue(client.getStatus().hasQueueDropsSinceLastSuccessfulExport());
            receiver.mode = LocalOtlpReceiver.Mode.NORMAL;
            receiver.release.countDown();
            client.flush();
            await(() -> receiver.accepted.get() == 2560, 10);
            assertEquals(0, receiver.duplicates.get());
            assertEquals(0, receiver.invalidRequests.get());
            assertEquals(LangfuseOtelStatus.ExportState.SUCCEEDED, client.getStatus().getExportState());
            assertFalse(client.getStatus().hasQueueDropsSinceLastSuccessfulExport());
            assertEquals(2048, client.getStatus().getQueueDroppedSpanCount(), "loss remains cumulative after recovery");
            evidence("saturation", client, receiver, Map.of("produced", 4608, "blockedProducerMs", producerMs));
        }
    }

    @Test void serviceUnavailableFailsThenFreshExportRecovers() throws Exception {
        failThenRecover(LocalOtlpReceiver.Mode.UNAVAILABLE);
    }

    @Test void disconnectedResponseFailsThenFreshExportRecovers() throws Exception {
        failThenRecover(LocalOtlpReceiver.Mode.DISCONNECT);
    }

    private void failThenRecover(LocalOtlpReceiver.Mode mode) throws Exception {
        try (LocalOtlpReceiver receiver = new LocalOtlpReceiver(true);
             LangfuseOtel client = receiver.client(false)) {
            receiver.mode = mode;
            emit(client, 1);
            long start = System.nanoTime();
            client.flush();
            double flushMs = elapsedMs(start);
            assertTrue(flushMs < 12000, "flush exceeded local 10s wait plus scheduling allowance");
            await(() -> client.getStatus().getExportState() == LangfuseOtelStatus.ExportState.FAILED, 35);
            assertEquals(1, client.getStatus().getFailedExportSpanCount());
            long attempts = receiver.requests.get();
            assertTrue(attempts >= 2, "retry path should be exercised");
            assertEquals(0, receiver.accepted.get());
            receiver.mode = LocalOtlpReceiver.Mode.NORMAL;
            emit(client, 1);
            client.flush();
            await(() -> client.getStatus().getExportState() == LangfuseOtelStatus.ExportState.SUCCEEDED, 10);
            assertEquals(1, receiver.accepted.get(), "failed span is not a durable resend queue");
            assertEquals(1, client.getStatus().getFailedExportSpanCount());
            assertEquals(0, client.getStatus().getQueueDroppedSpanCount());
            evidence(mode.name().toLowerCase(Locale.ROOT), client, receiver,
                    Map.of("firstFlushMs", flushMs, "failedRequestAttempts", attempts));
        }
    }

    @Test void slowReceiverDoesNotDelayObservationEnd() throws Exception {
        try (LocalOtlpReceiver receiver = new LocalOtlpReceiver(true);
             LangfuseOtel client = receiver.client(true)) {
            receiver.mode = LocalOtlpReceiver.Mode.BLOCK;
            emit(client, 512);
            assertTrue(receiver.entered.await(8, TimeUnit.SECONDS));
            LangfuseObservation observation = client.observation("delayed", ObservationType.GENERATION).start();
            long start = System.nanoTime();
            observation.end();
            double endMs = elapsedMs(start);
            assertTrue(endMs < 1000);
            assertEquals(0, receiver.accepted.get());
            receiver.mode = LocalOtlpReceiver.Mode.SLOW;
            receiver.release.countDown();
            client.flush();
            assertEquals(513, receiver.accepted.get());
            assertEquals(0, receiver.duplicates.get());
            evidence("slow", client, receiver, Map.of("endMsWhileNetworkBlocked", endMs));
        }
    }

    @Test void shutdownWaitIsBoundedDuringOutage() throws Exception {
        long initialWorkers = batchWorkers();
        try (LocalOtlpReceiver receiver = new LocalOtlpReceiver(false)) {
            LangfuseOtel client = receiver.client(false);
            try {
                receiver.mode = LocalOtlpReceiver.Mode.BLOCK;
                // Several batches ensure shutdown cannot drain everything within its local wait.
                emit(client, 2048);
                assertTrue(receiver.entered.await(8, TimeUnit.SECONDS));
                long start = System.nanoTime();
                client.close();
                double closeMs = elapsedMs(start);
                assertTrue(closeMs < 12000);
                evidence("shutdown", client, receiver, Map.of("closeMs", closeMs));
            } finally {
                receiver.mode = LocalOtlpReceiver.Mode.NORMAL;
                receiver.release.countDown();
                client.close();
                // close() bounds the caller's wait; shutdown may finish asynchronously afterwards.
                await(() -> batchWorkers() <= initialWorkers, 35);
            }
        }
    }

    private static long batchWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("BatchSpanProcessor"))
                .count();
    }

    static void emit(LangfuseOtel client, int count) {
        for (int i = 0; i < count; i++) client.observation("synthetic", ObservationType.GENERATION)
                .start().model("local-fixture").usage(5, 7).end();
    }
    static double elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "condition did not converge within " + seconds + "s");
    }
    static void evidence(String scenario, LangfuseOtel client, LocalOtlpReceiver receiver, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(extra);
        result.put("scenario", scenario);
        result.put("time", Instant.now().toString());
        result.put("status", client.getStatus());
        result.put("httpRequests", receiver.requests.get());
        result.put("acceptedSpans", receiver.accepted.get());
        result.put("duplicateAcceptedIds", receiver.duplicates.get());
        Path output = Paths.get("target", "operations", scenario + ".json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }
}
