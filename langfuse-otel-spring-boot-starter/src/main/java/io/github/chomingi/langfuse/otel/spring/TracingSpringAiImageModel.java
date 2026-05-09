package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import java.util.stream.Collectors;

public class TracingSpringAiImageModel implements ImageModel {

    private static final Logger log = LoggerFactory.getLogger(TracingSpringAiImageModel.class);

    private final ImageModel delegate;
    private final LangfuseOtel langfuseOtel;

    public TracingSpringAiImageModel(ImageModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public ImageResponse call(ImagePrompt prompt) {
        Span span;
        try {
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "image_generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "spring-ai")
                    .startSpan();
            setRequestAttributes(span, prompt);
        } catch (Exception e) {
            log.debug("Langfuse image instrumentation setup failed, proceeding without tracing", e);
            return delegate.call(prompt);
        }

        try {
            ImageResponse response = delegate.call(prompt);
            try {
                setResponseAttributes(span, response);
            } catch (Exception e) {
                log.debug("Failed to record image response attributes", e);
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
        return className.replace("ImageModel", "").replace("ImageClient", "")
                .toLowerCase() + ".image_generation";
    }

    private void setRequestAttributes(Span span, ImagePrompt prompt) {
        ImageOptions options = prompt.getOptions();
        if (options != null) {
            if (options.getModel() != null) {
                span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, options.getModel());
            }
        }

        if (prompt.getInstructions() != null && !prompt.getInstructions().isEmpty()) {
            String input = prompt.getInstructions().stream()
                    .map(msg -> msg.getText())
                    .collect(Collectors.joining("; "));
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, input);
        }
    }

    private void setResponseAttributes(Span span, ImageResponse response) {
        if (response == null) return;

        int count = response.getResults() != null ? response.getResults().size() : 0;
        span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, count + " image(s) generated");
    }

    private static void recordException(Span span, Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(t);
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
    }
}
