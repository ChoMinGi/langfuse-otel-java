package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.JsonUtils;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

public class TracingSpringAiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TracingSpringAiChatModel.class);

    private final ChatModel delegate;
    private final LangfuseOtel langfuseOtel;

    public TracingSpringAiChatModel(ChatModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        LangfuseGeneration gen = null;
        try {
            gen = new LangfuseGeneration(langfuseOtel.getTracer(), resolveSpanName());
            gen.system("spring-ai");
            setRequestAttributes(gen, prompt);
        } catch (Exception e) {
            log.debug("Langfuse instrumentation setup failed, proceeding without tracing", e);
        }

        try {
            ChatResponse response = delegate.call(prompt);
            try {
                setResponseAttributes(gen, response);
            } catch (Exception e) {
                log.debug("Failed to record response attributes", e);
            }
            return response;
        } catch (Throwable t) {
            if (gen != null) {
                try { gen.recordException(t); } catch (Exception ignored) {}
            }
            throw t;
        } finally {
            if (gen != null) {
                try { gen.end(); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private String resolveSpanName() {
        String className = delegate.getClass().getSimpleName();
        return className.replace("ChatModel", "").replace("ChatClient", "").toLowerCase() + ".chat";
    }

    private void setRequestAttributes(LangfuseGeneration gen, Prompt prompt) {
        if (gen == null) return;

        ChatOptions options = prompt.getOptions();
        if (options == null) {
            options = delegate.getDefaultOptions();
        }
        if (options != null) {
            if (options.getModel() != null) gen.model(options.getModel());
            if (options.getTemperature() != null) gen.temperature(options.getTemperature());
            if (options.getMaxTokens() != null) gen.maxTokens(options.getMaxTokens());
            if (options.getTopP() != null) gen.topP(options.getTopP());
        }

        gen.input(toJsonMessages(prompt.getInstructions()));
    }

    private String toJsonMessages(List<Message> messages) {
        StringBuilder inputBuilder = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i > 0) inputBuilder.append(",");
            inputBuilder.append("{\"role\":\"")
                    .append(msg.getMessageType().getValue())
                    .append("\",\"content\":\"")
                    .append(JsonUtils.escapeJson(msg.getText()))
                    .append("\"}");
        }
        inputBuilder.append("]");
        return inputBuilder.toString();
    }

    private void setResponseAttributes(LangfuseGeneration gen, ChatResponse response) {
        if (gen == null || response == null) return;

        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            if (metadata.getModel() != null) {
                gen.responseModel(metadata.getModel());
                gen.model(metadata.getModel());
            }

            Usage usage = metadata.getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null) gen.inputTokens(usage.getPromptTokens());
                if (usage.getCompletionTokens() != null) gen.outputTokens(usage.getCompletionTokens());
                if (usage.getTotalTokens() != null) gen.totalTokens(usage.getTotalTokens());
            }
        }

        if (response.getResult() != null && response.getResult().getOutput() != null) {
            String text = response.getResult().getOutput().getText();
            if (text != null) gen.output(text);
        }
    }
}
