package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class TracingLangChain4jEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(TracingLangChain4jEmbeddingModel.class);

    private final EmbeddingModel delegate;
    private final LangfuseOtel langfuseOtel;

    public TracingLangChain4jEmbeddingModel(EmbeddingModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        Span span;
        try {
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "embeddings")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            setRequestAttributes(span, textSegments);
        } catch (Exception e) {
            log.debug("Langfuse embedding instrumentation setup failed, proceeding without tracing", e);
            return delegate.embedAll(textSegments);
        }

        try {
            Response<List<Embedding>> response = delegate.embedAll(textSegments);
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

    private String resolveSpanName() {
        String className = delegate.getClass().getSimpleName();
        return className.replace("EmbeddingModel", "").replace("EmbeddingClient", "")
                .toLowerCase() + ".embeddings";
    }

    private void setRequestAttributes(Span span, List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) return;

        if (segments.size() == 1) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, segments.get(0).text());
        } else {
            String input = segments.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.joining(", ", "[", "]"));
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, input);
        }
    }

    private void setResponseAttributes(Span span, Response<List<Embedding>> response) {
        if (response == null) return;

        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            if (usage.inputTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) usage.inputTokenCount());
            if (usage.totalTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, (long) usage.totalTokenCount());
        }

        int embeddingCount = response.content() != null ? response.content().size() : 0;
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
