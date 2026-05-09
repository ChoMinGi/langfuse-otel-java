package io.github.chomingi.langfuse.otel;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TracingLangChain4jChatModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void chatRequestCallsCaptureParametersAndResponse() {
        ChatModel proxy = proxy(new StubLangChain4jChatModel());

        ChatResponse response = proxy.chat(ChatRequest.builder()
                .messages(SystemMessage.from("be concise"), UserMessage.from("hello"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gpt-4o-mini")
                        .temperature(0.2)
                        .topP(0.7)
                        .maxOutputTokens(64)
                        .build())
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("answer: hello");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).contains("\"role\":\"user\"");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(9L);
    }

    @Test
    void stringCallsUseDefaultParametersAndCaptureOutput() {
        ChatModel proxy = proxy(new StubLangChain4jChatModel());

        String response = proxy.chat("how are you?");

        assertThat(response).isEqualTo("answer: how are you?");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("answer: how are you?");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).contains("\"content\":\"how are you?\"");
    }

    @Test
    void messageListCallsSerializeInputMessages() {
        ChatModel proxy = proxy(new StubLangChain4jChatModel());

        proxy.chat(List.of(SystemMessage.from("system"), UserMessage.from("list-call")));

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("\"content\":\"list-call\"");
    }

    private ChatModel proxy(ChatModel target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingLangChain4jChatModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    static class StubLangChain4jChatModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            List<ChatMessage> messages = chatRequest.messages();
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            String text = lastMessage instanceof UserMessage
                    ? ((UserMessage) lastMessage).singleText()
                    : String.valueOf(lastMessage);

            return ChatResponse.builder()
                    .modelName("gpt-4o-mini")
                    .tokenUsage(new TokenUsage(4, 5, 9))
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("answer: " + text))
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
}
