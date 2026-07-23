package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseOtelStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Locale;

final class LangfuseOtelHealthIndicator implements HealthIndicator {

    private final LangfuseOtel langfuseOtel;

    LangfuseOtelHealthIndicator(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public Health health() {
        LangfuseOtelStatus status = langfuseOtel.getStatus();
        Health.Builder builder = healthBuilder(status)
                .withDetail("ownership", lower(status.getOpenTelemetryOwnership()))
                .withDetail("noopFallback", status.isNoopFallback())
                .withDetail("noopReason", lower(status.getNoopReason()));

        if (!status.isOperationalSignalsAvailable()) {
            return builder
                    .withDetail("operationalSignals", "not_managed")
                    .withDetail("exportState", "not_managed")
                    .withDetail("queue", "not_managed")
                    .withDetail("flushState", "not_managed")
                    .build();
        }

        return builder
                .withDetail("operationalSignals", "managed")
                .withDetail("exportState", lower(status.getExportState()))
                .withDetail("failedExportSpans", status.getFailedExportSpanCount())
                .withDetail("queueDroppedSpans", status.getQueueDroppedSpanCount())
                .withDetail("queueDropsSinceLastSuccessfulExport",
                        status.hasQueueDropsSinceLastSuccessfulExport())
                .withDetail("flushState", lower(status.getFlushState()))
                .withDetail("failedFlushes", status.getFailedFlushCount())
                .withDetail("timedOutFlushes", status.getTimedOutFlushCount())
                .build();
    }

    private static Health.Builder healthBuilder(LangfuseOtelStatus status) {
        if (status.isNoopFallback()) {
            return Health.outOfService();
        }
        if (status.getOpenTelemetryOwnership() == LangfuseOtel.OpenTelemetryOwnership.EXTERNAL
                || !status.isOperationalSignalsAvailable()) {
            return Health.unknown();
        }
        if (status.getExportState() == LangfuseOtelStatus.ExportState.FAILED
                || status.hasQueueDropsSinceLastSuccessfulExport()
                || status.getFlushState() == LangfuseOtelStatus.FlushState.FAILED
                || status.getFlushState() == LangfuseOtelStatus.FlushState.TIMED_OUT) {
            return Health.down();
        }
        return Health.up();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
