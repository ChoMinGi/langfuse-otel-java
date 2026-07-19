package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LangfuseOtelContentCaptureTest {

    private SdkTracerProvider externalTracerProvider;
    private InMemorySpanExporter exporter;

    @AfterEach
    void shutDownExternalProvider() {
        if (externalTracerProvider != null) {
            externalTracerProvider.shutdown();
        }
    }

    @Test
    void publicBuilderAndExternalBuilderDefaultToMetadataOnly() {
        try (LangfuseOtel standalone = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .build()) {
            assertMetadataOnly(standalone.getContentCapturePolicy());
        }

        try (LangfuseOtel external = LangfuseOtel.externalBuilder(externalOpenTelemetry()).build()) {
            assertMetadataOnly(external.getContentCapturePolicy());
        }
    }

    @Test
    void metadataOnlyDoesNotRecordAutomaticInputOrOutput() {
        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry()).build()) {
            Span span = langfuse.getTracer().spanBuilder("metadata-only").startSpan();
            langfuse.recordInput(span, "secret input");
            langfuse.recordOutput(span, "secret output");
            span.end();
        }

        SpanData span = onlySpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT)))
                .isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_OUTPUT)))
                .isNull();
    }

    @Test
    void spanHelperHonorsInputOnlyCapture() {
        ContentCapturePolicy policy = ContentCapturePolicy.builder()
                .captureInput(true)
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .contentCapturePolicy(policy)
                .build()) {
            Span span = langfuse.getTracer().spanBuilder("input-only").startSpan();
            langfuse.recordInput(span, "captured input");
            langfuse.recordOutput(span, "discarded output");
            span.end();
        }

        SpanData span = onlySpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT)))
                .isEqualTo("captured input");
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_OUTPUT)))
                .isNull();
    }

    @Test
    void generationHelperHonorsOutputOnlyCapture() {
        ContentCapturePolicy policy = ContentCapturePolicy.builder()
                .captureOutput(true)
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .contentCapturePolicy(policy)
                .build()) {
            try (LangfuseGeneration generation = new LangfuseGeneration(langfuse.getTracer(), "output-only")) {
                langfuse.recordInput(generation, "discarded input");
                langfuse.recordOutput(generation, "captured output");
            }
        }

        SpanData span = onlySpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT)))
                .isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_OUTPUT)))
                .isEqualTo("captured output");
    }

    @Test
    void redactsBeforeApplyingMaximumLength() {
        ContentCapturePolicy policy = ContentCapturePolicy.builder()
                .captureInput(true)
                .maxLength(10)
                .redactor((type, content) -> "redacted:" + content)
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .contentCapturePolicy(policy)
                .build()) {
            Span span = langfuse.getTracer().spanBuilder("redacted").startSpan();
            langfuse.recordInput(span, "secret");
            span.end();
        }

        assertThat(onlySpan().getAttributes()
                .get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT)))
                .isEqualTo("redacted:s");
    }

    @Test
    void redactorExceptionIsContainedAndContentIsDiscarded() {
        ContentCapturePolicy policy = ContentCapturePolicy.builder()
                .captureInput(true)
                .redactor((type, content) -> {
                    throw new IllegalStateException("redaction failed");
                })
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .contentCapturePolicy(policy)
                .build()) {
            Span span = langfuse.getTracer().spanBuilder("redactor-failure").startSpan();
            assertThatCode(() -> langfuse.recordInput(span, "secret"))
                    .doesNotThrowAnyException();
            span.end();
        }

        assertThat(onlySpan().getAttributes()
                .get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT)))
                .isNull();
    }

    @Test
    void redactorErrorIsContainedAndContentIsDiscarded() {
        ContentCapturePolicy policy = ContentCapturePolicy.builder()
                .captureOutput(true)
                .redactor((type, content) -> {
                    throw new AssertionError("redaction failed");
                })
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry())
                .contentCapturePolicy(policy)
                .build()) {
            Span span = langfuse.getTracer().spanBuilder("redactor-error").startSpan();
            assertThatCode(() -> langfuse.recordOutput(span, "secret"))
                    .doesNotThrowAnyException();
            span.end();
        }

        assertThat(onlySpan().getAttributes()
                .get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_OUTPUT)))
                .isNull();
    }

    @Test
    void manualFluentInputRemainsUnchangedUnderMetadataOnlyPolicy() {
        try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(externalOpenTelemetry()).build()) {
            try (LangfuseGeneration generation = new LangfuseGeneration(langfuse.getTracer(), "manual")) {
                generation.input("explicit manual input").output("explicit manual output");
            }
        }

        SpanData span = onlySpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT)))
                .isEqualTo("explicit manual input");
        assertThat(span.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_OUTPUT)))
                .isEqualTo("explicit manual output");
    }

    @Test
    void packagePrivateConstructorKeepsCaptureAllCompatibilityDefault() {
        try (LangfuseOtel langfuse = new LangfuseOtel(null, externalOpenTelemetry(), null, true)) {
            assertThat(langfuse.getContentCapturePolicy().isInputCaptureEnabled()).isTrue();
            assertThat(langfuse.getContentCapturePolicy().isOutputCaptureEnabled()).isTrue();
        }
    }

    private OpenTelemetry externalOpenTelemetry() {
        exporter = InMemorySpanExporter.create();
        externalTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(externalTracerProvider)
                .build();
    }

    private SpanData onlySpan() {
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        return exporter.getFinishedSpanItems().get(0);
    }

    private static void assertMetadataOnly(ContentCapturePolicy policy) {
        assertThat(policy.isInputCaptureEnabled()).isFalse();
        assertThat(policy.isOutputCaptureEnabled()).isFalse();
        assertThat(policy.getMaxLength()).isPositive();
    }
}
