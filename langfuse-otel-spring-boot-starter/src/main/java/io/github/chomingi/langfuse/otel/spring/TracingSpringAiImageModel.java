package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import java.util.stream.Collectors;

/**
 * Spring AI image model decorator that records Langfuse client spans.
 */
public class TracingSpringAiImageModel implements ImageModel {

    private static final Logger log = LoggerFactory.getLogger(TracingSpringAiImageModel.class);

    private final ImageModel delegate;
    private final LangfuseOtel langfuseOtel;

    /**
     * Creates a tracing decorator for a Spring AI image model.
     *
     * @param delegate model that performs image requests
     * @param langfuseOtel tracing integration
     */
    public TracingSpringAiImageModel(ImageModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public ImageResponse call(ImagePrompt prompt) {
        Span createdSpan = null;
        try {
            createdSpan = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "image_generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                    .startSpan();
            LangfuseContext.applyTo(createdSpan);
            setRequestAttributes(createdSpan, prompt);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(createdSpan);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse image instrumentation setup failed, proceeding without tracing", failure);
            return delegate.call(prompt);
        }

        Span span = createdSpan;
        try {
            ImageResponse response = SpanScopeSupport.call(span, () -> delegate.call(prompt));
            try {
                setResponseAttributes(span, response);
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                log.debug("Failed to record image response attributes", failure);
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

    private void setRequestAttributes(Span span, ImagePrompt prompt) {
        ImageOptions options = prompt.getOptions();
        if (options != null) {
            if (options.getModel() != null) {
                span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, options.getModel());
            }
        }

        if (langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()
                && prompt.getInstructions() != null && !prompt.getInstructions().isEmpty()) {
            String input = prompt.getInstructions().stream()
                    .map(msg -> msg.getText())
                    .collect(Collectors.joining("; "));
            langfuseOtel.recordInput(span, input);
        }
    }

    private void setResponseAttributes(Span span, ImageResponse response) {
        if (response == null) return;

        int count = response.getResults() != null ? response.getResults().size() : 0;
        langfuseOtel.recordOutput(span, count + " image(s) generated");
    }

}
