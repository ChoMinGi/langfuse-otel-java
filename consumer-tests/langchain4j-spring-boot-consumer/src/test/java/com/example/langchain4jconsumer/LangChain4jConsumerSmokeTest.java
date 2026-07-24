package com.example.langchain4jconsumer;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
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
                LangChain4jConsumerApplication.class,
                LangChain4jConsumerSmokeTest.TelemetryConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "langfuse.otel-mode=external")
// This smoke test uses no mocks and should not depend on JVM agent attachment.
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class LangChain4jConsumerSmokeTest {

    private final ChatModel chatModel;
    private final LangfuseOtel langfuseOtel;
    private final OpenTelemetry openTelemetry;
    private final InMemorySpanExporter spanExporter;

    @Autowired
    LangChain4jConsumerSmokeTest(ChatModel chatModel,
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
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(UserMessage.from("consumer smoke"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("langchain4j-smoke-model")
                        .build())
                .build());

        assertThat(langfuseOtel.isNoop()).isFalse();
        assertThat(langfuseOtel.getOpenTelemetryOwnership())
                .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.EXTERNAL);
        assertThat(response.aiMessage().text())
                .isEqualTo("langchain4j response: consumer smoke");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).singleElement().satisfies(span -> {
            assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(span.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.GEN_AI_OPERATION_NAME)))
                    .isEqualTo("chat");
            assertThat(span.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.GEN_AI_SYSTEM)))
                    .isEqualTo("langchain4j");
            assertThat(span.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.GEN_AI_REQUEST_MODEL)))
                    .isEqualTo("langchain4j-smoke-model");
            assertThat(span.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.GEN_AI_RESPONSE_MODEL)))
                    .isEqualTo("langchain4j-smoke-model");
            assertThat(span.getAttributes().get(
                    AttributeKey.longKey(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS)))
                    .isEqualTo(4L);
            assertThat(span.getAttributes().get(
                    AttributeKey.longKey(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS)))
                    .isEqualTo(5L);
            assertThat(span.getAttributes().get(
                    AttributeKey.longKey(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS)))
                    .isEqualTo(9L);
        });

        langfuseOtel.close();
        openTelemetry.getTracer("langchain4j-consumer-smoke")
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
