package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * LangChain4j embedding model decorator that records Langfuse client spans.
 */
public class TracingLangChain4jEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(TracingLangChain4jEmbeddingModel.class);

    private final EmbeddingModel delegate;
    private final LangfuseOtel langfuseOtel;

    /**
     * Creates a tracing decorator for a LangChain4j embedding model.
     *
     * @param delegate model that performs embedding requests
     * @param langfuseOtel tracing integration
     */
    public TracingLangChain4jEmbeddingModel(EmbeddingModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "embedding")
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "embeddings")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            setRequestAttributes(createdSpan, textSegments);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse embedding instrumentation setup failed, proceeding without tracing", failure);
            return delegate.embedAll(textSegments);
        }

        Span span = createdSpan;
        try {
            Response<List<Embedding>> response = SpanScopeSupport.call(span,
                    () -> delegate.embedAll(textSegments));
            try {
                setResponseAttributes(span, response);
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                log.debug("Failed to record embedding response attributes", failure);
            }
            return response;
        } catch (Throwable t) {
            InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, span, t);
            throw t;
        } finally {
            InstrumentationFailureSupport.endQuietly(span);
        }
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    private String resolveSpanName() {
        return ModelSpanNameSupport.resolve(
                delegate, "embeddings", "EmbeddingModel", "EmbeddingClient");
    }

    private void setRequestAttributes(Span span, List<TextSegment> segments) {
        if (!langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()) return;
        if (segments == null || segments.isEmpty()) return;

        if (segments.size() == 1) {
            langfuseOtel.recordInput(span, segments.get(0).text());
        } else {
            String input = segments.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.joining(", ", "[", "]"));
            langfuseOtel.recordInput(span, input);
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
        langfuseOtel.recordOutput(span, embeddingCount + " embedding(s)");
    }

}
