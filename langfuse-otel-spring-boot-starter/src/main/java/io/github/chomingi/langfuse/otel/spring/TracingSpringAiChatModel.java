package io.github.chomingi.langfuse.otel.spring;

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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public Flux<ChatResponse> stream(Prompt prompt) {
        Span span;
        try {
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "chat")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                    .startSpan();
            setRequestAttributesOnSpan(span, prompt);
        } catch (Exception e) {
            log.debug("Langfuse streaming instrumentation setup failed, proceeding without tracing", e);
            return delegate.stream(prompt);
        }

        AtomicBoolean spanEnded = new AtomicBoolean(false);
        StringBuilder accumulated = new StringBuilder();
        AtomicBoolean firstChunk = new AtomicBoolean(true);

        return delegate.stream(prompt)
                .doOnNext(chunk -> {
                    try {
                        if (firstChunk.compareAndSet(true, false)) {
                            span.setAttribute(LangfuseAttributes.OBSERVATION_COMPLETION_START_TIME,
                                    String.valueOf(System.currentTimeMillis()));
                        }
                        if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                            String text = chunk.getResult().getOutput().getText();
                            if (text != null) accumulated.append(text);
                        }
                        setStreamResponseAttributesOnSpan(span, chunk);
                    } catch (Exception e) {
                        log.debug("Failed to record streaming chunk attributes", e);
                    }
                })
                .doOnError(t -> {
                    try {
                        recordExceptionOnSpan(span, t);
                    } catch (Exception ignored) {}
                    endSpan(span, spanEnded);
                })
                .doOnComplete(() -> {
                    try {
                        if (accumulated.length() > 0) {
                            span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, accumulated.toString());
                        }
                    } catch (Exception ignored) {}
                    endSpan(span, spanEnded);
                })
                .doOnCancel(() -> endSpan(span, spanEnded));
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

    private void setRequestAttributesOnSpan(Span span, Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null) {
            options = delegate.getDefaultOptions();
        }
        if (options != null) {
            if (options.getModel() != null) span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, options.getModel());
            if (options.getTemperature() != null) span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TEMPERATURE, options.getTemperature());
            if (options.getMaxTokens() != null) span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MAX_TOKENS, (long) options.getMaxTokens());
            if (options.getTopP() != null) span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TOP_P, options.getTopP());
        }
        span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, toJsonMessages(prompt.getInstructions()));
    }

    private void setStreamResponseAttributesOnSpan(Span span, ChatResponse chunk) {
        if (chunk == null) return;
        ChatResponseMetadata metadata = chunk.getMetadata();
        if (metadata == null) return;

        if (metadata.getModel() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_RESPONSE_MODEL, metadata.getModel());
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, metadata.getModel());
        }
        Usage usage = metadata.getUsage();
        if (usage != null) {
            if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) usage.getPromptTokens());
            if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, (long) usage.getCompletionTokens());
            if (usage.getTotalTokens() != null && usage.getTotalTokens() > 0)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, (long) usage.getTotalTokens());
        }
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
