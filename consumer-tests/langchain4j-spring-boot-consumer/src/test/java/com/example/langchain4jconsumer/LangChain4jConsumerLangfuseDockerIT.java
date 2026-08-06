package com.example.langchain4jconsumer;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseOtelStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
@SpringBootTest(
        classes = LangChain4jConsumerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class LangChain4jConsumerLangfuseDockerIT {

    private final ChatModel chatModel;
    private final LangfuseOtel langfuseOtel;

    @Autowired
    LangChain4jConsumerLangfuseDockerIT(ChatModel chatModel, LangfuseOtel langfuseOtel) {
        this.chatModel = chatModel;
        this.langfuseOtel = langfuseOtel;
    }

    @DynamicPropertySource
    static void standaloneProperties(DynamicPropertyRegistry properties) {
        properties.add("langfuse.otel-mode", () -> "standalone");
        properties.add("langfuse.public-key",
                () -> requiredEnvironment("LANGFUSE_PUBLIC_KEY"));
        properties.add("langfuse.secret-key",
                () -> requiredEnvironment("LANGFUSE_SECRET_KEY"));
        properties.add("langfuse.host",
                () -> requiredEnvironment("LANGFUSE_HOST"));
        properties.add("langfuse.service-name", () -> "langchain4j-docker-e2e");
        properties.add("langfuse.content.capture-input", () -> "true");
        properties.add("langfuse.content.capture-output", () -> "true");
    }

    @Test
    void exportsAnAutomaticallyInstrumentedChatModelToLangfuse() {
        String marker = requiredEnvironment("LANGFUSE_E2E_MARKER");
        String commit = requiredEnvironment("LANGFUSE_E2E_COMMIT");
        assertThat(marker).matches("[A-Za-z0-9._-]{1,80}");
        assertThat(commit).matches("[0-9a-f]{40}");

        String traceName = "langchain4j-docker-e2e-" + marker;
        String promptText = "langchain4j-input-" + marker;
        AtomicReference<ChatResponse> response = new AtomicReference<>();

        langfuseOtel.trace(traceName, trace -> {
            trace.input("langchain4j-root-input-" + marker)
                    .version(commit)
                    .release("docker-e2e")
                    .environment("docker-e2e")
                    .metadata("framework", "langchain4j");

            response.set(chatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(promptText))
                    .parameters(DefaultChatRequestParameters.builder()
                            .modelName("langchain4j-smoke-model")
                            .build())
                    .build()));
            trace.output("langchain4j-root-output-" + marker);
        });

        assertThat(langfuseOtel.isNoop()).isFalse();
        assertThat(langfuseOtel.getOpenTelemetryOwnership())
                .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.OWNED);
        assertThat(response.get()).isNotNull();
        assertThat(response.get().aiMessage().text())
                .isEqualTo("langchain4j response: " + promptText);

        langfuseOtel.flush();

        LangfuseOtelStatus status = langfuseOtel.getStatus();
        assertThat(status.getExportState())
                .isEqualTo(LangfuseOtelStatus.ExportState.SUCCEEDED);
        assertThat(status.getFlushState())
                .isEqualTo(LangfuseOtelStatus.FlushState.SUCCEEDED);
        assertThat(status.getQueueDroppedSpanCount()).isZero();

        System.out.println("LANGFUSE_E2E_TRACE_NAME=" + traceName);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("%s is required for Docker E2E", name).isNotBlank();
        return value;
    }
}
