package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelRuntimeMeterProviderTest {

    @Test
    void batchProcessorSelfMetricsReportExactQueueDrops() throws Exception {
        LangfuseOtelRuntime runtime = LangfuseOtelRuntime.managed();
        LangfuseOtelRuntimeMeterProvider meterProvider = new LangfuseOtelRuntimeMeterProvider(runtime);
        BlockingExporter exporter = new BlockingExporter();
        BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setMeterProvider(meterProvider)
                .setScheduleDelay(1, TimeUnit.MILLISECONDS)
                .setMaxQueueSize(1)
                .setMaxExportBatchSize(1)
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .build();

        try {
            Tracer tracer = tracerProvider.get("queue-drop-contract");
            tracer.spanBuilder("in-flight").startSpan().end();
            assertThat(exporter.started.await(5, TimeUnit.SECONDS)).isTrue();

            tracer.spanBuilder("queued").startSpan().end();
            tracer.spanBuilder("dropped").startSpan().end();

            LangfuseOtelStatus saturated = runtime.snapshot(
                    LangfuseOtel.OpenTelemetryOwnership.OWNED, false);
            assertThat(saturated.getQueueDroppedSpanCount()).isEqualTo(1);
            assertThat(saturated.hasQueueDropsSinceLastSuccessfulExport()).isTrue();

            exporter.release.succeed();
            assertThat(tracerProvider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
        } finally {
            exporter.release.succeed();
            tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingExporter implements SpanExporter {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableResultCode release = new CompletableResultCode();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            started.countDown();
            return release;
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
