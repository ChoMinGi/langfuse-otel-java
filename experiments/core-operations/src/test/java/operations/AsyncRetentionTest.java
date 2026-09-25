package operations;

import io.github.chomingi.langfuse.otel.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncRetentionTest {
    @Test void cancelledObservationRemainsOwnedByPendingCallbackUntilProviderCompletes() throws Exception {
        try (LocalOtlpReceiver receiver = new LocalOtlpReceiver(false);
             LangfuseOtel client = receiver.client(false)) {
            CompletableFuture<String> provider = new CompletableFuture<>();
            WeakReference<LangfuseObservation> reference = attach(client, provider);
            cancel(reference);
            System.gc();
            assertFalse(provider.isDone(), "observation cancellation must not cancel provider work");
            assertNotNull(reference.get(), "the application's pending callback still owns the observation");
            provider.complete("late response");
            client.flush();
            awaitCollection(reference);
            assertEquals(1, receiver.accepted.get(), "late callback must not export a second terminal span");
        }
    }

    @Test void unreferencedUnfinishedObservationHasNoGlobalRegistryOwner() throws Exception {
        try (LocalOtlpReceiver receiver = new LocalOtlpReceiver(false);
             LangfuseOtel client = receiver.client(false)) {
            WeakReference<LangfuseObservation> reference = new WeakReference<>(
                    client.observation("abandoned", ObservationType.GENERATION).start());
            awaitCollection(reference);
            client.flush();
            assertEquals(0, receiver.accepted.get(), "collection is not an automatic end/export mechanism");
        }
    }

    private static WeakReference<LangfuseObservation> attach(LangfuseOtel client, CompletableFuture<String> provider) {
        LangfuseObservation observation = client.observation("pending", ObservationType.GENERATION).start();
        provider.whenComplete((response, failure) -> observation.end());
        return new WeakReference<>(observation);
    }
    private static void cancel(WeakReference<LangfuseObservation> reference) { reference.get().cancel(); }
    private static void awaitCollection(WeakReference<?> reference) throws InterruptedException {
        for (int i = 0; i < 20 && reference.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(reference.get(), "fixture reference remained reachable after idle GC");
    }
}
