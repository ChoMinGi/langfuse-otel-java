package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * LangChain4j image model decorator that records Langfuse client spans.
 */
public class TracingLangChain4jImageModel implements ImageModel {

    private static final Logger log = LoggerFactory.getLogger(TracingLangChain4jImageModel.class);

    private final ImageModel delegate;
    private final LangfuseOtel langfuseOtel;

    /**
     * Creates a tracing decorator for a LangChain4j image model.
     *
     * @param delegate model that performs image requests
     * @param langfuseOtel tracing integration
     */
    public TracingLangChain4jImageModel(ImageModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public Response<Image> generate(String prompt) {
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "image_generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            langfuseOtel.recordInput(createdSpan, prompt);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse image instrumentation setup failed, proceeding without tracing", failure);
            return delegate.generate(prompt);
        }

        Span span = createdSpan;
        try {
            Response<Image> response = SpanScopeSupport.call(span, () -> delegate.generate(prompt));
            try {
                langfuseOtel.recordOutput(span, "1 image generated");
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
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
    public Response<List<Image>> generate(String prompt, int n) {
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "image_generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            langfuseOtel.recordInput(createdSpan, prompt);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse image instrumentation setup failed, proceeding without tracing", failure);
            return delegate.generate(prompt, n);
        }

        Span span = createdSpan;
        try {
            Response<List<Image>> response = SpanScopeSupport.call(span, () -> delegate.generate(prompt, n));
            try {
                int count = response.content() != null ? response.content().size() : 0;
                langfuseOtel.recordOutput(span, count + " image(s) generated");
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
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
    public Response<Image> edit(Image image, String prompt) {
        return traceEdit(prompt, () -> delegate.edit(image, prompt));
    }

    @Override
    public Response<Image> edit(Image image, Image mask, String prompt) {
        return traceEdit(prompt, () -> delegate.edit(image, mask, prompt));
    }

    private Response<Image> traceEdit(String prompt, Supplier<Response<Image>> invocation) {
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "image_generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            langfuseOtel.recordInput(createdSpan, prompt);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse image edit instrumentation setup failed, proceeding without tracing", failure);
            return invocation.get();
        }

        Span span = createdSpan;
        try {
            Response<Image> response = SpanScopeSupport.call(span, invocation);
            try {
                langfuseOtel.recordOutput(span, "1 image edited");
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
            }
            return response;
        } catch (Throwable t) {
            InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, span, t);
            throw t;
        } finally {
            InstrumentationFailureSupport.endQuietly(span);
        }
    }

    private String resolveSpanName() {
        return ModelSpanNameSupport.resolve(
                delegate, "image_generation", "ImageModel", "ImageClient");
    }

}
