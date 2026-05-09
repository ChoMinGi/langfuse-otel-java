package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class TracingLangChain4jImageModel implements ImageModel {

    private static final Logger log = LoggerFactory.getLogger(TracingLangChain4jImageModel.class);

    private final ImageModel delegate;
    private final LangfuseOtel langfuseOtel;

    public TracingLangChain4jImageModel(ImageModel delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    @Override
    public Response<Image> generate(String prompt) {
        Span span;
        try {
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "image_generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, prompt);
        } catch (Exception e) {
            log.debug("Langfuse image instrumentation setup failed, proceeding without tracing", e);
            return delegate.generate(prompt);
        }

        try {
            Response<Image> response = delegate.generate(prompt);
            try {
                span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, "1 image generated");
            } catch (Exception ignored) {}
            return response;
        } catch (Throwable t) {
            try { recordException(span, t); } catch (Exception ignored) {}
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    public Response<List<Image>> generate(String prompt, int n) {
        Span span;
        try {
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "image_generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, prompt);
        } catch (Exception e) {
            log.debug("Langfuse image instrumentation setup failed, proceeding without tracing", e);
            return delegate.generate(prompt, n);
        }

        try {
            Response<List<Image>> response = delegate.generate(prompt, n);
            try {
                int count = response.content() != null ? response.content().size() : 0;
                span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, count + " image(s) generated");
            } catch (Exception ignored) {}
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

    private static void recordException(Span span, Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(t);
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
    }
}
