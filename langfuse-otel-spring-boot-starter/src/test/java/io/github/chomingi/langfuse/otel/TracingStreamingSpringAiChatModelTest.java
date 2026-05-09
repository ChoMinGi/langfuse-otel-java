package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TracingStreamingSpringAiChatModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void streamCapturesRequestAndResponseAttributes() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        Prompt prompt = new Prompt("What is Langfuse?", ChatOptions.builder()
                .model("gpt-4o-mini")
                .temperature(0.3)
                .maxTokens(128)
                .topP(0.8)
                .build());

        List<ChatResponse> responses = proxy.stream(prompt).collectList().block();

        assertThat(responses).hasSize(3);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("spring-ai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("\"role\":\"user\"").contains("What is Langfuse?");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("Hello World!");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(5L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(7L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(12L);
    }

    @Test
    void streamRecordsExceptionOnError() {
        ChatModel proxy = proxy(new ErrorStreamingSpringAiChatModel());

        Prompt prompt = new Prompt("fail");

        StepVerifier.create(proxy.stream(prompt))
                .expectError(RuntimeException.class)
                .verify();

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
    }

    @Test
    void syncCallStillWorks() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        ChatResponse response = proxy.call(new Prompt("hello", ChatOptions.builder()
                .model("gpt-4o-mini").build()));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("sync answer: hello");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("spring-ai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("sync answer: hello");
    }

    @Test
    void completionStartTimeIsRecorded() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        proxy.stream(new Prompt("hi")).collectList().block();

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        String startTime = span.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.completion_start_time"));
        assertThat(startTime).isNotNull();
        assertThat(Long.parseLong(startTime)).isGreaterThan(0);
    }

    @Test
    void streamCancellationEndsSpan() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        StepVerifier.create(proxy.stream(new Prompt("hello")))
                .expectNextCount(1)
                .thenCancel()
                .verify();

        assertThat(otel.getSpans()).hasSize(1);
    }

    private ChatModel proxy(ChatModel target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    static class StubStreamingSpringAiChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            String input = prompt.getInstructions().isEmpty() ? "" : prompt.getInstructions().get(0).getText();
            Usage usage = new StubUsage(5, 7);
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model("gpt-4o-mini")
                    .usage(usage)
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("sync answer: " + input))), metadata);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            ChatResponse chunk1 = new ChatResponse(
                    List.of(new Generation(new AssistantMessage("Hello"))));
            ChatResponse chunk2 = new ChatResponse(
                    List.of(new Generation(new AssistantMessage(" World"))));

            Usage finalUsage = new StubUsage(5, 7);
            ChatResponseMetadata finalMeta = ChatResponseMetadata.builder()
                    .model("gpt-4o-mini")
                    .usage(finalUsage)
                    .build();
            ChatResponse chunk3 = new ChatResponse(
                    List.of(new Generation(new AssistantMessage("!"))), finalMeta);

            return Flux.just(chunk1, chunk2, chunk3);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder()
                    .model("gpt-4o-mini")
                    .temperature(0.4)
                    .maxTokens(256)
                    .topP(0.9)
                    .build();
        }
    }

    static class ErrorStreamingSpringAiChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new RuntimeException("sync error");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(new RuntimeException("stream error"));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("gpt-4o-mini").build();
        }
    }

    static class StubUsage implements Usage {
        private final Integer promptTokens;
        private final Integer completionTokens;

        StubUsage(Integer promptTokens, Integer completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public Integer getPromptTokens() { return promptTokens; }

        @Override
        public Integer getCompletionTokens() { return completionTokens; }

        @Override
        public Object getNativeUsage() { return null; }
    }
}
