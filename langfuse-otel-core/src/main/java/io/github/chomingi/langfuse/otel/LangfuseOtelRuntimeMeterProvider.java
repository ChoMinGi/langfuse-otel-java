package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Converts the pinned OpenTelemetry SDK self-metrics into the stable runtime
 * snapshot exposed by this library. Unrelated instruments remain no-op.
 */
final class LangfuseOtelRuntimeMeterProvider implements MeterProvider {

    private static final String BATCH_PROCESSOR_SCOPE = "io.opentelemetry.sdk.trace";
    private static final String OTLP_HTTP_EXPORTER_SCOPE = "io.opentelemetry.exporters.otlp-http";
    private static final String PROCESSED_SPANS = "processedSpans";
    private static final String EXPORTER_EXPORTED = "otlp.exporter.exported";

    private static final AttributeKey<Boolean> DROPPED = AttributeKey.booleanKey("dropped");
    private static final AttributeKey<String> PROCESSOR_TYPE = AttributeKey.stringKey("processorType");
    private static final AttributeKey<String> TYPE = AttributeKey.stringKey("type");
    private static final AttributeKey<Boolean> SUCCESS = AttributeKey.booleanKey("success");

    private final MeterProvider delegate = MeterProvider.noop();
    private final LangfuseOtelRuntime runtime;

    LangfuseOtelRuntimeMeterProvider(LangfuseOtelRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public MeterBuilder meterBuilder(String instrumentationScopeName) {
        return new RuntimeMeterBuilder(
                instrumentationScopeName,
                delegate.meterBuilder(instrumentationScopeName),
                runtime);
    }

    private static final class RuntimeMeterBuilder implements MeterBuilder {

        private final String scopeName;
        private final MeterBuilder delegate;
        private final LangfuseOtelRuntime runtime;

        private RuntimeMeterBuilder(String scopeName, MeterBuilder delegate, LangfuseOtelRuntime runtime) {
            this.scopeName = scopeName;
            this.delegate = delegate;
            this.runtime = runtime;
        }

        @Override
        public MeterBuilder setSchemaUrl(String schemaUrl) {
            delegate.setSchemaUrl(schemaUrl);
            return this;
        }

        @Override
        public MeterBuilder setInstrumentationVersion(String instrumentationVersion) {
            delegate.setInstrumentationVersion(instrumentationVersion);
            return this;
        }

        @Override
        public Meter build() {
            return new RuntimeMeter(scopeName, delegate.build(), runtime);
        }
    }

    private static final class RuntimeMeter implements Meter {

        private final String scopeName;
        private final Meter delegate;
        private final LangfuseOtelRuntime runtime;

        private RuntimeMeter(String scopeName, Meter delegate, LangfuseOtelRuntime runtime) {
            this.scopeName = scopeName;
            this.delegate = delegate;
            this.runtime = runtime;
        }

        @Override
        public LongCounterBuilder counterBuilder(String name) {
            return new RuntimeCounterBuilder(scopeName, name, delegate.counterBuilder(name), runtime);
        }

        @Override
        public LongUpDownCounterBuilder upDownCounterBuilder(String name) {
            return delegate.upDownCounterBuilder(name);
        }

        @Override
        public DoubleHistogramBuilder histogramBuilder(String name) {
            return delegate.histogramBuilder(name);
        }

        @Override
        public DoubleGaugeBuilder gaugeBuilder(String name) {
            return delegate.gaugeBuilder(name);
        }
    }

    private static final class RuntimeCounterBuilder implements LongCounterBuilder {

        private final String scopeName;
        private final String name;
        private final LongCounterBuilder delegate;
        private final LangfuseOtelRuntime runtime;

        private RuntimeCounterBuilder(String scopeName, String name,
                                      LongCounterBuilder delegate, LangfuseOtelRuntime runtime) {
            this.scopeName = scopeName;
            this.name = name;
            this.delegate = delegate;
            this.runtime = runtime;
        }

        @Override
        public LongCounterBuilder setDescription(String description) {
            delegate.setDescription(description);
            return this;
        }

        @Override
        public LongCounterBuilder setUnit(String unit) {
            delegate.setUnit(unit);
            return this;
        }

        @Override
        public DoubleCounterBuilder ofDoubles() {
            return delegate.ofDoubles();
        }

        @Override
        public LongCounter build() {
            return new RuntimeCounter(scopeName, name, delegate.build(), runtime);
        }

        @Override
        public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
            return delegate.buildWithCallback(callback);
        }
    }

    private static final class RuntimeCounter implements LongCounter {

        private final String scopeName;
        private final String name;
        private final LongCounter delegate;
        private final LangfuseOtelRuntime runtime;

        private RuntimeCounter(String scopeName, String name, LongCounter delegate,
                               LangfuseOtelRuntime runtime) {
            this.scopeName = scopeName;
            this.name = name;
            this.delegate = delegate;
            this.runtime = runtime;
        }

        @Override
        public void add(long value) {
            delegate.add(value);
            record(value, Attributes.empty());
        }

        @Override
        public void add(long value, Attributes attributes) {
            delegate.add(value, attributes);
            record(value, attributes);
        }

        @Override
        public void add(long value, Attributes attributes, Context context) {
            delegate.add(value, attributes, context);
            record(value, attributes);
        }

        private void record(long value, Attributes attributes) {
            if (value <= 0 || attributes == null) {
                return;
            }
            if (BATCH_PROCESSOR_SCOPE.equals(scopeName) && PROCESSED_SPANS.equals(name)
                    && "BatchSpanProcessor".equals(attributes.get(PROCESSOR_TYPE))
                    && Boolean.TRUE.equals(attributes.get(DROPPED))) {
                runtime.recordQueueDropped(value);
                return;
            }
            if (!OTLP_HTTP_EXPORTER_SCOPE.equals(scopeName)
                    || !"span".equals(attributes.get(TYPE))) {
                return;
            }
            if (EXPORTER_EXPORTED.equals(name)) {
                Boolean success = attributes.get(SUCCESS);
                if (success != null) {
                    runtime.recordExportCompleted(success, value);
                }
            }
        }
    }
}
