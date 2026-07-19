package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.ContentCapturePolicy;
import io.github.chomingi.langfuse.otel.JsonUtils;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
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
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(gen);
            gen = null;
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse instrumentation setup failed, proceeding without tracing", failure);
        }

        try {
            ChatResponse response = delegate.call(prompt);
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
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.deferContextual(reactorContext -> {
            Context parentContext;
            LangfuseTraceContext traceContext;
            Span createdSpan = null;
            AtomicBoolean spanEnded;
            BoundedTextAccumulator accumulated;
            AtomicBoolean firstChunk;
            AtomicBoolean streamCompleted;
            AtomicReference<Throwable> streamFailure;
            Context invocationContext;
            try {
                parentContext = reactorContext.getOrDefault(
                        Context.class, Context.current());
                traceContext = reactorContext.getOrDefault(
                        LangfuseContext.reactorContextKey(),
                        LangfuseContext.from(parentContext));
                if (traceContext == null) {
                    traceContext = LangfuseContext.current();
                }
                if (traceContext != null) {
                    parentContext = LangfuseContext.storeIn(parentContext, traceContext);
                }
                ContentCapturePolicy capturePolicy = Objects.requireNonNull(
                        langfuseOtel.getContentCapturePolicy(),
                        "langfuseOtel.getContentCapturePolicy() must not return null");
                createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                        .setParent(parentContext)
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "chat")
                        .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                        .startSpan();
                LangfuseContext.applyTo(createdSpan,
                        traceContext != null ? traceContext : LangfuseContext.current());
                setRequestAttributesOnSpan(createdSpan, prompt, capturePolicy);
                spanEnded = new AtomicBoolean(false);
                accumulated = capturePolicy.isOutputCaptureEnabled()
                        ? new BoundedTextAccumulator(capturePolicy.getMaxLength())
                        : null;
                firstChunk = new AtomicBoolean(true);
                streamCompleted = new AtomicBoolean(false);
                streamFailure = new AtomicReference<>();
                invocationContext = parentContext.with(createdSpan);
            } catch (Throwable failure) {
                InstrumentationFailureSupport.endQuietly(createdSpan);
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                log.debug("Langfuse streaming instrumentation setup failed, proceeding without tracing", failure);
                return delegate.stream(prompt);
            }

            Span span = createdSpan;

            Flux<ChatResponse> delegateStream;
            try {
                delegateStream = Objects.requireNonNull(
                        SpanScopeSupport.call(invocationContext, () -> delegate.stream(prompt)),
                        "delegate.stream(prompt) must not return null");
            } catch (Throwable t) {
                InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, span, t);
                endSpan(span, spanEnded);
                Exceptions.throwIfFatal(t);
                return Flux.error(t);
            }

            Flux<ChatResponse> contextBridgedStream = ReactorSpanContextBridge.bridge(
                    delegateStream, invocationContext, traceContext);

            return contextBridgedStream
                    .doOnNext(chunk -> {
                        try {
                            if (firstChunk.compareAndSet(true, false)) {
                                span.setAttribute(LangfuseAttributes.OBSERVATION_COMPLETION_START_TIME,
                                        java.time.Instant.now().toString());
                            }
                            if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                                String text = chunk.getResult().getOutput().getText();
                                if (text != null && accumulated != null) accumulated.append(text);
                            }
                            setStreamResponseAttributesOnSpan(span, chunk);
                        } catch (Throwable failure) {
                            InstrumentationFailureSupport.rethrowIfFatal(failure);
                            log.debug("Failed to record streaming chunk attributes", failure);
                        }
                    })
                    .doOnError(streamFailure::set)
                    .doOnComplete(() -> streamCompleted.set(true))
                    .doFinally(signalType -> {
                        try {
                            Throwable failure = streamFailure.getAndSet(null);
                            if (failure != null) {
                                InstrumentationFailureSupport.recordExceptionQuietly(
                                        langfuseOtel, span, failure);
                            } else if (streamCompleted.get()
                                    && accumulated != null
                                    && !accumulated.overflowed()
                                    && accumulated.length() > 0) {
                                langfuseOtel.recordOutput(span, accumulated.toString());
                            }
                        } catch (Throwable failure) {
                            InstrumentationFailureSupport.rethrowIfFatal(failure);
                        } finally {
                            endSpan(span, spanEnded);
                        }
                    });
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private String resolveSpanName() {
        return ModelSpanNameSupport.resolve(delegate, "chat", "ChatModel", "ChatClient");
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

        if (langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()) {
            langfuseOtel.recordInput(gen, toJsonMessages(prompt.getInstructions()));
        }
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
            if (text != null) langfuseOtel.recordOutput(gen, text);
        }
    }

    private void setRequestAttributesOnSpan(Span span, Prompt prompt,
                                            ContentCapturePolicy capturePolicy) {
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
        if (capturePolicy.isInputCaptureEnabled()) {
            langfuseOtel.recordInput(span, toJsonMessages(prompt.getInstructions()));
        }
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

    private static void endSpan(Span span, AtomicBoolean spanEnded) {
        if (spanEnded.compareAndSet(false, true)) {
            InstrumentationFailureSupport.endQuietly(span);
        }
    }
}
