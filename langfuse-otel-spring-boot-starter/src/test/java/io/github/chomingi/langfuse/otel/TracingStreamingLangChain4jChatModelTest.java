package io.github.chomingi.langfuse.otel;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TracingStreamingLangChain4jChatModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void streamingChatCapturesRequestAndResponseAttributes() throws Exception {
        StreamingChatModel proxy = streamingProxy(new StubStreamingLangChain4jChatModel());

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder accumulated = new StringBuilder();

        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from("be concise"), UserMessage.from("hello"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gpt-4o-mini")
                        .temperature(0.2)
                        .topP(0.7)
                        .maxOutputTokens(64)
                        .build())
                .build();

        proxy.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                accumulated.append(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        ChatResponse response = future.get(5, TimeUnit.SECONDS);

        assertThat(accumulated.toString()).isEqualTo("HelloWorld");
        assertThat(response.aiMessage().text()).isEqualTo("HelloWorld");
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("\"role\":\"user\"").contains("hello");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("HelloWorld");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(4L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(5L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(9L);
    }

    @Test
    void streamingChatRecordsErrorOnHandlerError() throws Exception {
        StreamingChatModel proxy = streamingProxy(new ErrorStreamingLangChain4jChatModel());

        CompletableFuture<Void> future = new CompletableFuture<>();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("fail"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(null);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        assertThat(future).isCompletedExceptionally();
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
    }

    @Test
    void completionStartTimeIsRecorded() throws Exception {
        StreamingChatModel proxy = streamingProxy(new StubStreamingLangChain4jChatModel());

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        future.get(5, TimeUnit.SECONDS);
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        String startTime = span.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.completion_start_time"));
        assertThat(startTime).isNotNull();
        assertThat(java.time.Instant.parse(startTime)).isBefore(java.time.Instant.now());
    }

    @Test
    void syncChatStillWorks() {
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new StubStreamingLangChain4jChatModel(),
                        new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));

        ChatResponse response = ((ChatModel) proxy).chat(ChatRequest.builder()
                .messages(UserMessage.from("sync-test"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gpt-4o-mini")
                        .build())
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("sync: sync-test");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("sync: sync-test");
    }

    private StreamingChatModel streamingProxy(Object target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    static class StubStreamingLangChain4jChatModel implements StreamingChatModel, ChatModel {

        @Override
        public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
            return java.util.Set.of();
        }

        @Override
        public dev.langchain4j.model.ModelProvider provider() {
            return null;
        }

        @Override
        public java.util.List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
            return java.util.List.of();
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("Hello");
            handler.onPartialResponse("World");
            handler.onCompleteResponse(ChatResponse.builder()
                    .modelName("gpt-4o-mini")
                    .aiMessage(AiMessage.from("HelloWorld"))
                    .tokenUsage(new TokenUsage(4, 5, 9))
                    .build());
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            java.util.List<ChatMessage> messages = chatRequest.messages();
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            String text = lastMessage instanceof UserMessage
                    ? ((UserMessage) lastMessage).singleText()
                    : String.valueOf(lastMessage);

            return ChatResponse.builder()
                    .modelName("gpt-4o-mini")
                    .tokenUsage(new TokenUsage(4, 5, 9))
                    .aiMessage(AiMessage.from("sync: " + text))
                    .build();
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder()
                    .modelName("gpt-4o-mini")
                    .temperature(0.3)
                    .topP(0.8)
                    .maxOutputTokens(128)
                    .build();
        }
    }

    static class ErrorStreamingLangChain4jChatModel implements StreamingChatModel {

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onError(new RuntimeException("streaming error"));
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder()
                    .modelName("gpt-4o-mini")
                    .build();
        }
    }
}
