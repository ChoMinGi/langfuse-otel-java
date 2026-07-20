package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseOtelStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangfuseOtelMeterBinderTest {

    @Test
    void managedMetersFollowCoreSnapshotWithoutRebinding() {
        AtomicLong failedExports = new AtomicLong();
        AtomicLong queueDrops = new AtomicLong();
        AtomicLong flushFailures = new AtomicLong();
        AtomicLong flushTimeouts = new AtomicLong();
        AtomicReference<LangfuseOtelStatus.FlushState> flushState =
                new AtomicReference<>(LangfuseOtelStatus.FlushState.NOT_REQUESTED);

        LangfuseOtelStatus status = mock(LangfuseOtelStatus.class);
        when(status.getOpenTelemetryOwnership())
                .thenReturn(LangfuseOtel.OpenTelemetryOwnership.OWNED);
        when(status.isNoopFallback()).thenReturn(false);
        when(status.getNoopReason()).thenReturn(LangfuseOtelStatus.NoopReason.NONE);
        when(status.isOperationalSignalsAvailable()).thenReturn(true);
        when(status.getFailedExportSpanCount()).thenAnswer(ignored -> failedExports.get());
        when(status.getQueueDroppedSpanCount()).thenAnswer(ignored -> queueDrops.get());
        when(status.getFailedFlushCount()).thenAnswer(ignored -> flushFailures.get());
        when(status.getTimedOutFlushCount()).thenAnswer(ignored -> flushTimeouts.get());
        when(status.getFlushState()).thenAnswer(ignored -> flushState.get());

        LangfuseOtel langfuse = mock(LangfuseOtel.class);
        when(langfuse.getStatus()).thenReturn(status);
        when(langfuse.isNoop()).thenReturn(false);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new LangfuseOtelMeterBinder(langfuse).bindTo(registry);

            assertThat(registry.get("langfuse.otel.noop.fallback")
                    .tag("ownership", "owned")
                    .tag("fallback_reason", "none")
                    .gauge().value()).isZero();
            assertThat(registry.get("langfuse.otel.export.failed.spans")
                    .functionCounter().count()).isZero();
            assertThat(registry.get("langfuse.otel.queue.dropped.spans")
                    .functionCounter().count()).isZero();
            assertThat(registry.get("langfuse.otel.flush.state")
                    .tag("state", "not_requested").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("langfuse.otel.flush.state")
                    .tag("state", "in_progress").gauge().value()).isZero();

            failedExports.set(3);
            queueDrops.set(2);
            flushFailures.set(1);
            flushTimeouts.set(1);
            flushState.set(LangfuseOtelStatus.FlushState.IN_PROGRESS);

            assertThat(registry.get("langfuse.otel.export.failed.spans")
                    .functionCounter().count()).isEqualTo(3.0);
            assertThat(registry.get("langfuse.otel.queue.dropped.spans")
                    .functionCounter().count()).isEqualTo(2.0);
            assertThat(registry.get("langfuse.otel.flush.failures")
                    .functionCounter().count()).isEqualTo(1.0);
            assertThat(registry.get("langfuse.otel.flush.timeouts")
                    .functionCounter().count()).isEqualTo(1.0);
            assertThat(registry.get("langfuse.otel.flush.state")
                    .tag("state", "not_requested").gauge().value()).isZero();
            assertThat(registry.get("langfuse.otel.flush.state")
                    .tag("state", "in_progress").gauge().value()).isEqualTo(1.0);

            flushState.set(LangfuseOtelStatus.FlushState.SUCCEEDED);
            assertThat(registry.get("langfuse.otel.flush.state")
                    .tag("state", "in_progress").gauge().value()).isZero();
            assertThat(registry.get("langfuse.otel.flush.state")
                    .tag("state", "succeeded")
                    .gauge().value()).isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }
}
