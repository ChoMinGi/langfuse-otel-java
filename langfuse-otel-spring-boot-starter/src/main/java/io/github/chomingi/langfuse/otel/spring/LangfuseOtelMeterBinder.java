package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseOtelStatus;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Locale;

final class LangfuseOtelMeterBinder implements MeterBinder {

    private final LangfuseOtel langfuseOtel;

    LangfuseOtelMeterBinder(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        LangfuseOtelStatus initial = langfuseOtel.getStatus();
        Tags runtimeTags = Tags.of(
                "ownership", lower(initial.getOpenTelemetryOwnership()),
                "fallback_reason", lower(initial.getNoopReason()));

        Gauge.builder("langfuse.otel.noop.fallback", langfuseOtel,
                        source -> source.getStatus().isNoopFallback() ? 1.0 : 0.0)
                .description("Whether fail-safe construction produced the Langfuse OTel no-op fallback")
                .tags(runtimeTags)
                .register(registry);

        if (!initial.isOperationalSignalsAvailable()) {
            return;
        }

        FunctionCounter.builder("langfuse.otel.export.failed.spans", langfuseOtel,
                        source -> source.getStatus().getFailedExportSpanCount())
                .description("Spans contained in standalone exporter calls reported as failed")
                .baseUnit("spans")
                .register(registry);
        FunctionCounter.builder("langfuse.otel.queue.dropped.spans", langfuseOtel,
                        source -> source.getStatus().getQueueDroppedSpanCount())
                .description("Spans dropped because the standalone export queue was full")
                .baseUnit("spans")
                .register(registry);
        FunctionCounter.builder("langfuse.otel.flush.failures", langfuseOtel,
                        source -> source.getStatus().getFailedFlushCount())
                .description("Owned SDK flush waits that failed or were interrupted")
                .baseUnit("operations")
                .register(registry);
        FunctionCounter.builder("langfuse.otel.flush.timeouts", langfuseOtel,
                        source -> source.getStatus().getTimedOutFlushCount())
                .description("Owned SDK flush waits that timed out")
                .baseUnit("operations")
                .register(registry);
        for (LangfuseOtelStatus.FlushState state : LangfuseOtelStatus.FlushState.values()) {
            if (state == LangfuseOtelStatus.FlushState.NOT_MANAGED) {
                continue;
            }
            Gauge.builder("langfuse.otel.flush.state", langfuseOtel,
                            source -> source.getStatus().getFlushState() == state ? 1.0 : 0.0)
                    .description("Current owned SDK flush state")
                    .tag("state", lower(state))
                    .register(registry);
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
