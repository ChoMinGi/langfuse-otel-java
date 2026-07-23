package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
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
import java.util.Objects;
import java.util.stream.Collectors;

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
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "embeddings")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            setRequestAttributes(createdSpan, request);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse embedding instrumentation setup failed, proceeding without tracing", failure);
            return delegate.call(request);
        }

        Span span = createdSpan;
        try {
            EmbeddingResponse response = SpanScopeSupport.call(span, () -> delegate.call(request));
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
    public float[] embed(Document document) {
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "embeddings")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            if (document != null) {
                langfuseOtel.recordInput(createdSpan, document.getText());
            }
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse document embedding instrumentation setup failed, proceeding without tracing", failure);
            return Objects.requireNonNull(
                    delegate.embed(document),
                    "Spring AI EmbeddingModel delegate returned null from embed(Document)");
        }

        Span span = createdSpan;
        try {
            float[] embedding = Objects.requireNonNull(
                    SpanScopeSupport.call(span, () -> delegate.embed(document)),
                    "Spring AI EmbeddingModel delegate returned null from embed(Document)");
            try {
                langfuseOtel.recordOutput(span,
                        "1 embedding (" + embedding.length + " dimensions)");
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
            }
            return embedding;
        } catch (Throwable t) {
            InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, span, t);
            throw t;
        } finally {
            InstrumentationFailureSupport.endQuietly(span);
        }
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options,
                               BatchingStrategy batchingStrategy) {
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "embeddings")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            setBulkRequestAttributes(createdSpan, documents, options);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse bulk embedding instrumentation setup failed, proceeding without tracing", failure);
            return Objects.requireNonNull(
                    delegate.embed(documents, options, batchingStrategy),
                    "Spring AI EmbeddingModel delegate returned null from bulk embed");
        }

        Span span = createdSpan;
        try {
            List<float[]> embeddings = Objects.requireNonNull(
                    SpanScopeSupport.call(span,
                            () -> delegate.embed(documents, options, batchingStrategy)),
                    "Spring AI EmbeddingModel delegate returned null from bulk embed");
            int embeddingCount = embeddings.size();
            try {
                langfuseOtel.recordOutput(span, embeddingCount + " embedding(s)");
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
            }
            return embeddings;
        } catch (Throwable failure) {
            InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, span, failure);
            throw failure;
        } finally {
            InstrumentationFailureSupport.endQuietly(span);
        }
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    private String resolveSpanName() {
        return ModelSpanNameSupport.resolve(
                delegate, "embeddings", "EmbeddingModel", "EmbeddingClient");
    }

    private void setRequestAttributes(Span span, EmbeddingRequest request) {
        EmbeddingOptions options = request.getOptions();
        if (options != null) {
            if (options.getModel() != null) {
                span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, options.getModel());
            }
        }

        if (!langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()) return;
        List<String> inputs = request.getInstructions();
        if (inputs != null && !inputs.isEmpty()) {
            if (inputs.size() == 1) {
                langfuseOtel.recordInput(span, inputs.get(0));
            } else {
                langfuseOtel.recordInput(span, String.valueOf(inputs));
            }
        }
    }

    private void setBulkRequestAttributes(Span span, List<Document> documents, EmbeddingOptions options) {
        if (options != null && options.getModel() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, options.getModel());
        }

        if (!langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()
                || documents == null || documents.isEmpty()) {
            return;
        }
        String input = documents.stream()
                .map(document -> document == null ? "null" : String.valueOf(document.getText()))
                .collect(Collectors.joining(", ", "[", "]"));
        langfuseOtel.recordInput(span, input);
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
        langfuseOtel.recordOutput(span, embeddingCount + " embedding(s)");
    }

}
