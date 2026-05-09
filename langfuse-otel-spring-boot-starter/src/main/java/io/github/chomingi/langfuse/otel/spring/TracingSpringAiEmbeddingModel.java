package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

public class TracingSpringAiEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(TracingSpringAiEmbeddingModel.class);

    private final EmbeddingModel delegate;
    private final LangfuseOtel langfuseOtel;

    public TracingSpringAiEmbeddingModel(EmbeddingModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Span span;
        try {
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "embeddings")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                    .startSpan();
            setRequestAttributes(span, request);
        } catch (Exception e) {
            log.debug("Langfuse embedding instrumentation setup failed, proceeding without tracing", e);
            return delegate.call(request);
        }

        try {
            EmbeddingResponse response = delegate.call(request);
            try {
                setResponseAttributes(span, response);
            } catch (Exception e) {
                log.debug("Failed to record embedding response attributes", e);
            }
            return response;
        } catch (Throwable t) {
            try { recordException(span, t); } catch (Exception ignored) {}
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    private String resolveSpanName() {
        String className = delegate.getClass().getSimpleName();
        return className.replace("EmbeddingModel", "").replace("EmbeddingClient", "")
                .toLowerCase() + ".embeddings";
    }

    private void setRequestAttributes(Span span, EmbeddingRequest request) {
        EmbeddingOptions options = request.getOptions();
        if (options != null) {
            if (options.getModel() != null) {
                span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, options.getModel());
            }
        }

        List<String> inputs = request.getInstructions();
        if (inputs != null && !inputs.isEmpty()) {
            if (inputs.size() == 1) {
                span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, inputs.get(0));
            } else {
                span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(inputs));
            }
        }
    }

    private void setResponseAttributes(Span span, EmbeddingResponse response) {
        if (response == null) return;

        EmbeddingResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            if (metadata.getModel() != null) {
                span.setAttribute(LangfuseAttributes.GEN_AI_RESPONSE_MODEL, metadata.getModel());
                span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, metadata.getModel());
            }
            Usage usage = metadata.getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null)
                    span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) usage.getPromptTokens());
                if (usage.getTotalTokens() != null)
                    span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, (long) usage.getTotalTokens());
            }
        }

        int embeddingCount = response.getResults() != null ? response.getResults().size() : 0;
        span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, embeddingCount + " embedding(s)");
    }

    private static void recordException(Span span, Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(t);
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
    }
}
