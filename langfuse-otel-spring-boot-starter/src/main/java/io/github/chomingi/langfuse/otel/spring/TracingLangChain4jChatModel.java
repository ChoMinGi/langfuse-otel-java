package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.JsonUtils;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * LangChain4j chat model decorator that records Langfuse generation spans.
 */
public class TracingLangChain4jChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TracingLangChain4jChatModel.class);

    private final ChatModel delegate;
    private final LangfuseOtel langfuseOtel;

    /**
     * Creates a tracing decorator for a LangChain4j chat model.
     *
     * @param delegate model that performs chat requests
     * @param langfuseOtel tracing integration
     */
    public TracingLangChain4jChatModel(ChatModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        LangfuseGeneration gen = null;
        try {
            gen = new LangfuseGeneration(langfuseOtel.getTracer(), resolveSpanName());
            gen.system("langchain4j");
            setRequestAttributes(gen, chatRequest);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(gen);
            gen = null;
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse instrumentation setup failed, proceeding without tracing", failure);
        }

        try {
            ChatResponse response = delegate.chat(chatRequest);
            try {
                setResponseAttributes(gen, response);
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                log.debug("Failed to record response attributes", failure);
            }
            return response;
        } catch (Throwable t) {
            if (gen != null) {
                InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, gen, t);
            }
            throw t;
        } finally {
            if (gen != null) {
                InstrumentationFailureSupport.endQuietly(gen);
            }
        }
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private String resolveSpanName() {
        return ModelSpanNameSupport.resolve(delegate, "chat", "ChatModel", "ChatLanguageModel");
    }

    private void setRequestAttributes(LangfuseGeneration gen, ChatRequest request) {
        if (gen == null) return;

        ChatRequestParameters effectiveParameters = defaultRequestParameters();
        if (request.parameters() != null) {
            effectiveParameters = effectiveParameters.overrideWith(request.parameters());
        }
        if (effectiveParameters.modelName() != null) {
            gen.model(effectiveParameters.modelName());
        }
        if (effectiveParameters.temperature() != null) {
            gen.temperature(effectiveParameters.temperature());
        }
        if (effectiveParameters.maxOutputTokens() != null) {
            gen.maxTokens(effectiveParameters.maxOutputTokens());
        }
        if (effectiveParameters.topP() != null) {
            gen.topP(effectiveParameters.topP());
        }

        List<ChatMessage> messages = request.messages();
        if (langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()
                && messages != null && !messages.isEmpty()) {
            langfuseOtel.recordInput(gen, toJsonMessages(messages));
        }
    }

    private String toJsonMessages(List<ChatMessage> messages) {
        StringBuilder inputBuilder = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (i > 0) inputBuilder.append(",");
            inputBuilder.append("{\"role\":\"")
                    .append(msg.type().name().toLowerCase())
                    .append("\",\"content\":\"")
                    .append(JsonUtils.escapeJson(messageContent(msg)))
                    .append("\"}");
        }
        inputBuilder.append("]");
        return inputBuilder.toString();
    }

    private String messageContent(ChatMessage message) {
        if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            if (userMessage.hasSingleText()) {
                return userMessage.singleText();
            }
        }
        if (message instanceof SystemMessage) {
            return ((SystemMessage) message).text();
        }
        if (message instanceof AiMessage) {
            return ((AiMessage) message).text();
        }
        return String.valueOf(message);
    }

    private void setResponseAttributes(LangfuseGeneration gen, ChatResponse response) {
        if (gen == null || response == null) return;

        if (response.modelName() != null) {
            gen.responseModel(response.modelName());
            gen.model(response.modelName());
        }

        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            if (usage.inputTokenCount() != null) gen.inputTokens(usage.inputTokenCount());
            if (usage.outputTokenCount() != null) gen.outputTokens(usage.outputTokenCount());
            if (usage.totalTokenCount() != null) gen.totalTokens(usage.totalTokenCount());
        }

        if (response.aiMessage() != null && response.aiMessage().text() != null) {
            langfuseOtel.recordOutput(gen, response.aiMessage().text());
        }
    }
}
