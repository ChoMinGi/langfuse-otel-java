package com.example.consumer;

import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {
                SpringBootConsumerApplication.class,
                SpringBootConsumerSmokeTest.TelemetryConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "langfuse.otel-mode=external")
// This smoke test uses no mocks and should not depend on JVM agent attachment.
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class SpringBootConsumerSmokeTest {

    private final ChatModel chatModel;
    private final LangfuseOtel langfuseOtel;
    private final OpenTelemetry openTelemetry;
    private final InMemorySpanExporter spanExporter;

    @Autowired
    SpringBootConsumerSmokeTest(ChatModel chatModel,
                                LangfuseOtel langfuseOtel,
                                OpenTelemetry openTelemetry,
                                InMemorySpanExporter spanExporter) {
        this.chatModel = chatModel;
        this.langfuseOtel = langfuseOtel;
        this.openTelemetry = openTelemetry;
        this.spanExporter = spanExporter;
    }

    @Test
    void startsAndAutomaticallyInstrumentsTheConsumerChatModel() {
        ChatResponse response = chatModel.call(new Prompt(
                "consumer smoke",
                ChatOptions.builder().model("smoke-model").build()));

        assertThat(langfuseOtel.isNoop()).isFalse();
        assertThat(langfuseOtel.getOpenTelemetryOwnership())
                .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.EXTERNAL);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("smoke response");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).singleElement().satisfies(span -> {
            assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(span.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.GEN_AI_OPERATION_NAME)))
                    .isEqualTo("chat");
            assertThat(span.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.GEN_AI_SYSTEM)))
                    .isEqualTo("spring-ai");
            assertThat(span.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.GEN_AI_REQUEST_MODEL)))
                    .isEqualTo("smoke-model");
        });

        langfuseOtel.close();
        openTelemetry.getTracer("consumer-smoke")
                .spanBuilder("external-after-langfuse-close")
                .startSpan()
                .end();

        List<SpanData> spansAfterClose = spanExporter.getFinishedSpanItems();
        assertThat(spansAfterClose).hasSize(2);
        assertThat(spansAfterClose.get(1).getName())
                .isEqualTo("external-after-langfuse-close");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TelemetryConfiguration {

        @Bean(destroyMethod = "")
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean(destroyMethod = "shutdown")
        SdkTracerProvider tracerProvider(InMemorySpanExporter spanExporter) {
            return SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();
        }

        @Bean(destroyMethod = "")
        OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
            return OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
        }
    }
}
