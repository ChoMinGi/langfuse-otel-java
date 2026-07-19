package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseOtelOwnershipTest {

    private SdkTracerProvider externalTracerProvider;

    @AfterEach
    void shutDownExternalProvider() {
        if (externalTracerProvider != null) {
            externalTracerProvider.shutdown();
        }
    }

    @Test
    void externalBuilderUsesApplicationProvidedOpenTelemetryWithoutKeys() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry externalOpenTelemetry = externalOpenTelemetry(exporter);

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry).build()) {
            assertThat(langfuse.isNoop()).isFalse();
            assertThat(langfuse.getOpenTelemetryOwnership())
                    .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.EXTERNAL);
            assertThat(langfuse.ownsOpenTelemetry()).isFalse();

            langfuse.trace("external-trace", trace -> {});
        }

        assertThat(exporter.getFinishedSpanItems())
                .extracting(span -> span.getName())
                .containsExactly("external-trace");
    }

    @Test
    void closeAndFlushDoNotOwnOrShutdownExternalOpenTelemetry() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry externalOpenTelemetry = externalOpenTelemetry(exporter);
        LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry).build();

        langfuse.flush();
        langfuse.close();

        Tracer applicationTracer = externalOpenTelemetry.getTracer("application-after-langfuse-close");
        applicationTracer.spanBuilder("application-span").startSpan().end();

        assertThat(exporter.getFinishedSpanItems())
                .extracting(span -> span.getName())
                .containsExactly("application-span");
    }

    @Test
    void existingBuilderRemainsStandaloneAndOwnsItsSdk() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .build()) {
            assertThat(langfuse.isNoop()).isFalse();
            assertThat(langfuse.getOpenTelemetryOwnership())
                    .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.OWNED);
            assertThat(langfuse.ownsOpenTelemetry()).isTrue();
        }
    }

    @Test
    void missingKeysRemainNoopWithoutOwnedSdk() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder().build()) {
            assertThat(langfuse.isNoop()).isTrue();
            assertThat(langfuse.getOpenTelemetryOwnership())
                    .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.NONE);
            assertThat(langfuse.ownsOpenTelemetry()).isFalse();
        }
    }

    @Test
    void externalBuilderHonorsFailSafeWhenTracerInitializationFails() {
        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(throwingOpenTelemetry()).build()) {
            assertThat(langfuse.isNoop()).isTrue();
            assertThat(langfuse.getOpenTelemetryOwnership())
                    .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.NONE);
            assertThat(langfuse.ownsOpenTelemetry()).isFalse();
        }
    }

    @Test
    void externalBuilderCanOptIntoStrictTracerInitialization() {
        assertThatThrownBy(() -> LangfuseOtel.externalBuilder(throwingOpenTelemetry())
                .failSafe(false)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("external tracer initialization failed");
    }

    private OpenTelemetry externalOpenTelemetry(InMemorySpanExporter exporter) {
        externalTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(externalTracerProvider)
                .build();
    }

    private static OpenTelemetry throwingOpenTelemetry() {
        return new OpenTelemetry() {
            @Override
            public TracerProvider getTracerProvider() {
                return TracerProvider.noop();
            }

            @Override
            public ContextPropagators getPropagators() {
                return ContextPropagators.noop();
            }

            @Override
            public Tracer getTracer(String instrumentationScopeName, String instrumentationScopeVersion) {
                throw new IllegalStateException("external tracer initialization failed");
            }
        };
    }
}
