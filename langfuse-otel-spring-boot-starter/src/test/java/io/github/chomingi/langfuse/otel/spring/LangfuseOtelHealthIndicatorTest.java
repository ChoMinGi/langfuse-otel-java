package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseOtelStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangfuseOtelHealthIndicatorTest {

    @Test
    void currentOwnedPipelineFailuresAreDownAndLaterSuccessRecovers() {
        LangfuseOtel langfuse = mock(LangfuseOtel.class);
        LangfuseOtelStatus runtime = ownedRuntime();
        when(langfuse.getStatus()).thenReturn(runtime);
        LangfuseOtelHealthIndicator indicator = new LangfuseOtelHealthIndicator(langfuse);

        when(runtime.getExportState()).thenReturn(LangfuseOtelStatus.ExportState.FAILED);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(runtime.getExportState()).thenReturn(LangfuseOtelStatus.ExportState.SUCCEEDED);
        when(runtime.hasQueueDropsSinceLastSuccessfulExport()).thenReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(runtime.hasQueueDropsSinceLastSuccessfulExport()).thenReturn(false);
        when(runtime.getFlushState()).thenReturn(LangfuseOtelStatus.FlushState.FAILED);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(runtime.getFlushState()).thenReturn(LangfuseOtelStatus.FlushState.TIMED_OUT);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(runtime.getFlushState()).thenReturn(LangfuseOtelStatus.FlushState.SUCCEEDED);
        Health recovered = indicator.health();
        assertThat(recovered.getStatus()).isEqualTo(Status.UP);
        assertThat(recovered.getDetails())
                .containsEntry("exportState", "succeeded")
                .containsEntry("flushState", "succeeded");
    }

    private static LangfuseOtelStatus ownedRuntime() {
        LangfuseOtelStatus runtime = mock(LangfuseOtelStatus.class);
        when(runtime.getOpenTelemetryOwnership())
                .thenReturn(LangfuseOtel.OpenTelemetryOwnership.OWNED);
        when(runtime.isNoopFallback()).thenReturn(false);
        when(runtime.getNoopReason()).thenReturn(LangfuseOtelStatus.NoopReason.NONE);
        when(runtime.isOperationalSignalsAvailable()).thenReturn(true);
        when(runtime.getExportState()).thenReturn(LangfuseOtelStatus.ExportState.NOT_ATTEMPTED);
        when(runtime.getFlushState()).thenReturn(LangfuseOtelStatus.FlushState.NOT_REQUESTED);
        return runtime;
    }
}
