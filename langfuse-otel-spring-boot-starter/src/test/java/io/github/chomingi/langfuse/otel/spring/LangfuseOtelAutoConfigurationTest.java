package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.ContentRedactor;
import io.github.chomingi.langfuse.otel.ExceptionRedactor;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGenerationAspect;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelAutoConfigurationTest {

    private final ApplicationContextRunner baseContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LangfuseOtelAutoConfiguration.class,
                    SpringAiAutoConfiguration.class,
                    LangChain4jAutoConfiguration.class));

    private final ApplicationContextRunner contextRunner = baseContextRunner
            .withPropertyValues(
                    "langfuse.public-key=pk-test",
                    "langfuse.secret-key=sk-test",
                    "langfuse.service-name=test-service");

    @Test
    void starterRegistersCoreAndAspectBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LangfuseOtel.class);
            assertThat(context).hasSingleBean(ObserveGenerationAspect.class);
            assertThat(context).hasSingleBean(SpringAiChatModelBeanPostProcessor.class);
            assertThat(context).hasSingleBean(LangChain4jChatModelBeanPostProcessor.class);
            assertThat(context).hasSingleBean(LangfuseContextFilter.class);
        });
    }

    @Test
    void starterCanBeDisabled() {
        contextRunner.withPropertyValues("langfuse.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LangfuseOtel.class);
                    assertThat(context).doesNotHaveBean(ObserveGenerationAspect.class);
                });
    }

    @Test
    void contentCaptureUsesSafeDefaults() {
        contextRunner.run(context -> {
            LangfuseOtel langfuse = context.getBean(LangfuseOtel.class);

            assertThat(langfuse.getContentCapturePolicy().isInputCaptureEnabled()).isFalse();
            assertThat(langfuse.getContentCapturePolicy().isOutputCaptureEnabled()).isFalse();
            assertThat(langfuse.getContentCapturePolicy().getMaxLength()).isEqualTo(8_192);
            assertThat(langfuse.getExceptionCapturePolicy().isMessageCaptureEnabled()).isFalse();
            assertThat(langfuse.getExceptionCapturePolicy().isStackTraceCaptureEnabled()).isFalse();
            assertThat(langfuse.getOpenTelemetryOwnership())
                    .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.OWNED);
        });
    }

    @Test
    void contentPropertiesAndUniqueRedactorBeanAreApplied() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk externalOpenTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        baseContextRunner
                .withBean(OpenTelemetry.class, () -> externalOpenTelemetry)
                .withBean(ContentRedactor.class,
                        () -> (type, content) -> type.name() + ":" + content)
                .withBean(ExceptionRedactor.class,
                        () -> (type, content) -> type.name() + ":" + content)
                .withPropertyValues(
                        "langfuse.content.capture-input=true",
                        "langfuse.content.capture-output=true",
                        "langfuse.content.max-length=12",
                        "langfuse.exception.capture-message=true",
                        "langfuse.exception.capture-stack-trace=true",
                        "langfuse.exception.max-length=12")
                .run(context -> {
                    LangfuseOtel langfuse = context.getBean(LangfuseOtel.class);
                    assertThat(langfuse.getOpenTelemetryOwnership())
                            .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.EXTERNAL);
                    assertThat(langfuse.getContentCapturePolicy().isInputCaptureEnabled()).isTrue();
                    assertThat(langfuse.getContentCapturePolicy().isOutputCaptureEnabled()).isTrue();
                    assertThat(langfuse.getContentCapturePolicy().getMaxLength()).isEqualTo(12);
                    assertThat(langfuse.getExceptionCapturePolicy().isMessageCaptureEnabled()).isTrue();
                    assertThat(langfuse.getExceptionCapturePolicy().isStackTraceCaptureEnabled()).isTrue();
                    assertThat(langfuse.getExceptionCapturePolicy().getMaxLength()).isEqualTo(12);

                    Span span = langfuse.getTracer().spanBuilder("redacted-content").startSpan();
                    langfuse.recordInput(span, "secret");
                    langfuse.recordOutput(span, "result");
                    langfuse.recordException(span, new IllegalStateException("secret"));
                    span.end();

                    assertThat(exporter.getFinishedSpanItems()).hasSize(1);
                    SpanData exported = exporter.getFinishedSpanItems().get(0);
                    assertThat(exported.getAttributes().get(AttributeKey.stringKey(
                            LangfuseAttributes.OBSERVATION_INPUT))).isEqualTo("INPUT:secret");
                    assertThat(exported.getAttributes().get(AttributeKey.stringKey(
                            LangfuseAttributes.OBSERVATION_OUTPUT))).isEqualTo("OUTPUT:resul");
                    EventData exceptionEvent = exported.getEvents().get(0);
                    assertThat(exceptionEvent.getAttributes().get(
                            AttributeKey.stringKey("exception.message"))).isEqualTo("MESSAGE:secr");
                    assertThat(exceptionEvent.getAttributes().get(
                            AttributeKey.stringKey("exception.stacktrace")))
                            .startsWith("STACK_TRACE:")
                            .hasSize(12);
                });
    }

    @Test
    void uniqueExternalOpenTelemetryIsPreferredAndDoesNotRequireKeys() {
        OpenTelemetry externalOpenTelemetry = OpenTelemetry.noop();

        baseContextRunner
                .withBean(OpenTelemetry.class, () -> externalOpenTelemetry)
                .run(context -> {
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    LangfuseOtel langfuse = context.getBean(LangfuseOtel.class);

                    assertThat(langfuse.isNoop()).isFalse();
                    assertThat(langfuse.getOpenTelemetryOwnership())
                            .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.EXTERNAL);
                    assertThat(langfuse.ownsOpenTelemetry()).isFalse();
                });
    }

    @Test
    void standaloneModeDoesNotReuseApplicationOpenTelemetry() {
        baseContextRunner
                .withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .withPropertyValues(
                        "langfuse.otel-mode=standalone",
                        "langfuse.public-key=pk-test",
                        "langfuse.secret-key=sk-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    assertThat(context.getBean(LangfuseOtel.class).getOpenTelemetryOwnership())
                            .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.OWNED);
                });
    }

    @Test
    void standaloneDevelopmentHttpRequiresAndUsesExplicitSpringOptIn() {
        baseContextRunner
                .withPropertyValues(
                        "langfuse.otel-mode=standalone",
                        "langfuse.public-key=pk-test",
                        "langfuse.secret-key=sk-test",
                        "langfuse.host=http://127.0.0.1:4318")
                .run(context -> {
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    assertThat(context.getBean(LangfuseOtel.class).isNoop()).isTrue();
                });

        baseContextRunner
                .withPropertyValues(
                        "langfuse.otel-mode=standalone",
                        "langfuse.public-key=pk-test",
                        "langfuse.secret-key=sk-test",
                        "langfuse.host=http://127.0.0.1:4318",
                        "langfuse.allow-insecure-http-for-development=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    LangfuseOtel langfuse = context.getBean(LangfuseOtel.class);
                    assertThat(langfuse.isNoop()).isFalse();
                    assertThat(langfuse.getOpenTelemetryOwnership())
                            .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.OWNED);
                });
    }

    @Test
    void externalModeRequiresExactlyOneApplicationOpenTelemetryBean() {
        baseContextRunner
                .withPropertyValues("langfuse.otel-mode=external")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("langfuse.otel-mode=external requires exactly one unambiguous "
                                    + "OpenTelemetry bean, but found 0. Select a primary bean or set "
                                    + "langfuse.otel-mode=standalone.");
                });
    }

    @Test
    void autoModeRejectsAmbiguousApplicationOpenTelemetryBeans() {
        baseContextRunner
                .withBean("firstOpenTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
                .withBean("secondOpenTelemetry", OpenTelemetry.class, OpenTelemetry::noop)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("langfuse.otel-mode=auto requires exactly one unambiguous "
                                    + "OpenTelemetry bean, but found 2. Select a primary bean or set "
                                    + "langfuse.otel-mode=standalone.");
                });
    }

    @Test
    void multipleRedactorsFailClosedInsteadOfExportingRawContent() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk externalOpenTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        try {
            baseContextRunner
                    .withBean(OpenTelemetry.class, () -> externalOpenTelemetry)
                    .withBean("firstRedactor", ContentRedactor.class,
                            () -> (type, content) -> "first:" + content)
                    .withBean("secondRedactor", ContentRedactor.class,
                            () -> (type, content) -> "second:" + content)
                    .withBean("firstExceptionRedactor", ExceptionRedactor.class,
                            () -> (type, content) -> "first:" + content)
                    .withBean("secondExceptionRedactor", ExceptionRedactor.class,
                            () -> (type, content) -> "second:" + content)
                    .withPropertyValues(
                            "langfuse.content.capture-input=true",
                            "langfuse.exception.capture-message=true")
                    .run(context -> {
                        LangfuseOtel langfuse = context.getBean(LangfuseOtel.class);
                        Span span = langfuse.getTracer().spanBuilder("ambiguous-redactors").startSpan();
                        langfuse.recordInput(span, "must-not-leak");
                        langfuse.recordException(span, new IllegalStateException("must-not-leak"));
                        span.end();

                        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
                        assertThat(exporter.getFinishedSpanItems().get(0).getAttributes().get(
                                AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT))).isNull();
                        assertThat(exporter.getFinishedSpanItems().get(0).getEvents().get(0)
                                .getAttributes().get(AttributeKey.stringKey("exception.message"))).isNull();
                    });
        } finally {
            tracerProvider.shutdown();
        }
    }
}
