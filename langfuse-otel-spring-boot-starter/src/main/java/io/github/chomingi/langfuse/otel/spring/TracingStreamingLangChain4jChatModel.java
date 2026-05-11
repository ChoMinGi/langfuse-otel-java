package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.JsonUtils;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class TracingStreamingLangChain4jChatModel implements StreamingChatModel, ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TracingStreamingLangChain4jChatModel.class);

    private final Object delegate;
    private final LangfuseOtel langfuseOtel;

    public TracingStreamingLangChain4jChatModel(Object delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    // --- StreamingChatModel ---

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        Span span;
        try {
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "chat")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            setRequestAttributesOnSpan(span, chatRequest);
        } catch (Exception e) {
            log.debug("Langfuse streaming instrumentation setup failed, proceeding without tracing", e);
            ((StreamingChatModel) delegate).doChat(chatRequest, handler);
            return;
        }

        AtomicBoolean spanEnded = new AtomicBoolean(false);
        StringBuffer accumulated = new StringBuffer();
        AtomicBoolean firstChunk = new AtomicBoolean(true);

        StreamingChatResponseHandler tracingHandler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    if (firstChunk.compareAndSet(true, false)) {
                        span.setAttribute(LangfuseAttributes.OBSERVATION_COMPLETION_START_TIME,
                                java.time.Instant.now().toString());
                    }
                    accumulated.append(partialResponse);
                } catch (Exception ignored) {}
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                try {
                    setResponseAttributesOnSpan(span, response, accumulated);
                } catch (Exception ignored) {}
                endSpan(span, spanEnded);
                handler.onCompleteResponse(response);
            }

            @Override
            public void onError(Throwable error) {
                try {
                    recordExceptionOnSpan(span, error);
                } catch (Exception ignored) {}
                endSpan(span, spanEnded);
                handler.onError(error);
            }
        };

        try {
            ((StreamingChatModel) delegate).doChat(chatRequest, tracingHandler);
        } catch (Throwable t) {
            try { recordExceptionOnSpan(span, t); } catch (Exception ignored) {}
            endSpan(span, spanEnded);
            throw t;
        }
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        if (delegate instanceof StreamingChatModel) {
            return ((StreamingChatModel) delegate).defaultRequestParameters();
        }
        if (delegate instanceof ChatModel) {
            return ((ChatModel) delegate).defaultRequestParameters();
        }
        return StreamingChatModel.super.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        if (delegate instanceof StreamingChatModel) {
            return ((StreamingChatModel) delegate).listeners();
        }
        if (delegate instanceof ChatModel) {
            return ((ChatModel) delegate).listeners();
        }
        return StreamingChatModel.super.listeners();
    }

    @Override
    public ModelProvider provider() {
        if (delegate instanceof StreamingChatModel) {
            return ((StreamingChatModel) delegate).provider();
        }
        if (delegate instanceof ChatModel) {
            return ((ChatModel) delegate).provider();
        }
        return null;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        if (delegate instanceof StreamingChatModel) {
            return ((StreamingChatModel) delegate).supportedCapabilities();
        }
        if (delegate instanceof ChatModel) {
            return ((ChatModel) delegate).supportedCapabilities();
        }
        return StreamingChatModel.super.supportedCapabilities();
    }

    // --- ChatModel (sync) ---

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        if (!(delegate instanceof ChatModel)) {
            throw new UnsupportedOperationException(
                    "Synchronous chat is not supported — the delegate only implements StreamingChatModel");
        }

        ChatModel syncDelegate = (ChatModel) delegate;
        LangfuseGeneration gen = null;
        try {
            gen = new LangfuseGeneration(langfuseOtel.getTracer(), resolveSpanName());
            gen.system("langchain4j");
            setRequestAttributes(gen, chatRequest);
        } catch (Exception e) {
            log.debug("Langfuse instrumentation setup failed, proceeding without tracing", e);
        }

        try {
            ChatResponse response = syncDelegate.doChat(chatRequest);
            try {
                setSyncResponseAttributes(gen, response);
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

    // --- Helpers ---

    private String resolveSpanName() {
        String className = delegate.getClass().getSimpleName();
        return className.replaceAll("ChatModel$|ChatLanguageModel$", "")
                .toLowerCase() + ".chat";
    }

    private void setRequestAttributesOnSpan(Span span, ChatRequest request) {
        ChatRequestParameters effectiveParameters = defaultRequestParameters();
        if (request.parameters() != null) {
            effectiveParameters = effectiveParameters.overrideWith(request.parameters());
        }
        if (effectiveParameters.modelName() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, effectiveParameters.modelName());
        }
        if (effectiveParameters.temperature() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TEMPERATURE, effectiveParameters.temperature());
        }
        if (effectiveParameters.maxOutputTokens() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MAX_TOKENS, (long) effectiveParameters.maxOutputTokens());
        }
        if (effectiveParameters.topP() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TOP_P, effectiveParameters.topP());
        }

        List<ChatMessage> messages = request.messages();
        if (messages != null && !messages.isEmpty()) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, toJsonMessages(messages));
        }
    }

    private void setRequestAttributes(LangfuseGeneration gen, ChatRequest request) {
        if (gen == null) return;

        ChatRequestParameters effectiveParameters = defaultRequestParameters();
        if (request.parameters() != null) {
            effectiveParameters = effectiveParameters.overrideWith(request.parameters());
        }
        if (effectiveParameters.modelName() != null) gen.model(effectiveParameters.modelName());
        if (effectiveParameters.temperature() != null) gen.temperature(effectiveParameters.temperature());
        if (effectiveParameters.maxOutputTokens() != null) gen.maxTokens(effectiveParameters.maxOutputTokens());
        if (effectiveParameters.topP() != null) gen.topP(effectiveParameters.topP());

        List<ChatMessage> messages = request.messages();
        if (messages != null && !messages.isEmpty()) {
            gen.input(toJsonMessages(messages));
        }
    }

    private void setResponseAttributesOnSpan(Span span, ChatResponse response, CharSequence accumulated) {
        if (response == null) return;

        if (response.modelName() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_RESPONSE_MODEL, response.modelName());
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, response.modelName());
        }

        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            if (usage.inputTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) usage.inputTokenCount());
            if (usage.outputTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, (long) usage.outputTokenCount());
            if (usage.totalTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, (long) usage.totalTokenCount());
        }

        // Prefer complete response text, fall back to accumulated partials
        String output = null;
        if (response.aiMessage() != null && response.aiMessage().text() != null) {
            output = response.aiMessage().text();
        } else if (accumulated.length() > 0) {
            output = accumulated.toString();
        }
        if (output != null) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, output);
        }
    }

    private void setSyncResponseAttributes(LangfuseGeneration gen, ChatResponse response) {
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
            gen.output(response.aiMessage().text());
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

    private static void recordExceptionOnSpan(Span span, Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(t);
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
    }

    private static void endSpan(Span span, AtomicBoolean spanEnded) {
        if (spanEnded.compareAndSet(false, true)) {
            span.end();
        }
    }
}
