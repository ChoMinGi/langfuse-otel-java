package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingSpringAiChatModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void promptCallsCapturePromptMessagesAndResponseMetadata() {
        ChatModel proxy = proxy(new StubSpringAiChatModel());

        ChatResponse response = proxy.call(new Prompt("What is Langfuse?", ChatOptions.builder()
                .model("gpt-4o-mini")
                .temperature(0.3)
                .maxTokens(128)
                .topP(0.8)
                .build()));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("answer: What is Langfuse?");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("spring-ai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("\"role\":\"user\"");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(12L);
    }

    @Test
    void stringCallsUseDefaultOptionsAndCaptureOutput() {
        ChatModel proxy = proxy(new StubSpringAiChatModel());

        String response = proxy.call("hello");

        assertThat(response).isEqualTo("answer: hello");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("answer: hello");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).contains("\"content\":\"hello\"");
    }

    @Test
    void messageArrayCallsSerializeMessages() {
        ChatModel proxy = proxy(new StubSpringAiChatModel());

        proxy.call(new Message[] { new UserMessage("msg-array") });

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("\"content\":\"msg-array\"");
    }

    @Test
    void publicBuilderDefaultsAutomaticInstrumentationToMetadataOnly() {
        LangfuseOtel langfuse = LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build();
        ChatModel proxy = proxy(new StubSpringAiChatModel(), langfuse);

        proxy.call(new Prompt("sensitive prompt"));

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isNull();
    }

    @Test
    void automaticContentCanBeRedactedAndEnabledPerDirection() {
        ContentCapturePolicy policy = ContentCapturePolicy.builder()
                .captureInput(true)
                .captureOutput(false)
                .redactor((type, content) -> "[REDACTED]")
                .build();
        LangfuseOtel langfuse = LangfuseOtel.externalBuilder(otel.getOpenTelemetry())
                .contentCapturePolicy(policy)
                .build();
        ChatModel proxy = proxy(new StubSpringAiChatModel(), langfuse);

        proxy.call(new Prompt("sensitive prompt"));

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isEqualTo("[REDACTED]");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isNull();
    }

    @Test
    void nonFatalSetupErrorEndsGenerationRestoresScopeAndFallsBackToDelegate() {
        ChatModel proxy = proxy(new NonFatalSetupErrorSpringAiChatModel());

        ChatResponse response = proxy.call(new Prompt("fallback"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("answer: fallback");
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void fatalSetupErrorEndsGenerationRestoresScopeAndIsRethrown() {
        FatalSetupErrorSpringAiChatModel target = new FatalSetupErrorSpringAiChatModel();
        ChatModel proxy = proxy(target);

        assertThatThrownBy(() -> proxy.call(new Prompt("fatal")))
                .isInstanceOf(LinkageError.class)
                .hasMessage("default options linkage failure");

        assertThat(target.callInvocations()).isZero();
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    private ChatModel proxy(ChatModel target) {
        return proxy(target, new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    private ChatModel proxy(ChatModel target, LangfuseOtel langfuseOtel) {
        return new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                target,
                langfuseOtel);
    }

    static class StubSpringAiChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            String input = prompt.getInstructions().isEmpty() ? "" : prompt.getInstructions().get(0).getText();
            Usage usage = new StubUsage(5, 7);
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model("gpt-4o-mini")
                    .usage(usage)
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer: " + input))), metadata);
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

    static class NonFatalSetupErrorSpringAiChatModel extends StubSpringAiChatModel {
        @Override
        public ChatOptions getDefaultOptions() {
            throw new AssertionError("default options unavailable");
        }
    }

    static class FatalSetupErrorSpringAiChatModel extends StubSpringAiChatModel {
        private final AtomicInteger callInvocations = new AtomicInteger();

        @Override
        public ChatOptions getDefaultOptions() {
            throw new LinkageError("default options linkage failure");
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            callInvocations.incrementAndGet();
            return super.call(prompt);
        }

        int callInvocations() {
            return callInvocations.get();
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
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
