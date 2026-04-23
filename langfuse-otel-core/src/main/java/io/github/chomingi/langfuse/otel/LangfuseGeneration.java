package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

public class LangfuseGeneration implements AutoCloseable {

    private final Span span;
    private final Scope scope;
    private final java.lang.ref.Cleaner.Cleanable cleanable;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LangfuseGeneration(Tracer tracer, String name) {
        this.span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "chat")
                .startSpan();
        this.scope = span.makeCurrent();
        this.cleanable = SpanGuard.register(this, span, scope, name);
    }

    public LangfuseGeneration model(String model) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, model);
        return this;
    }

    public LangfuseGeneration responseModel(String model) {
        span.setAttribute(LangfuseAttributes.GEN_AI_RESPONSE_MODEL, model);
        return this;
    }

    public LangfuseGeneration system(String system) {
        span.setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, system);
        return this;
    }

    public LangfuseGeneration temperature(double temperature) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TEMPERATURE, temperature);
        return this;
    }

    public LangfuseGeneration maxTokens(int maxTokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MAX_TOKENS, (long) maxTokens);
        return this;
    }

    public LangfuseGeneration topP(double topP) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TOP_P, topP);
        return this;
    }

    public LangfuseGeneration input(Object input) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(input));
        return this;
    }

    public LangfuseGeneration output(Object output) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, String.valueOf(output));
        return this;
    }

    public LangfuseGeneration inputTokens(int tokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) tokens);
        return this;
    }

    public LangfuseGeneration outputTokens(int tokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, (long) tokens);
        return this;
    }

    public LangfuseGeneration totalTokens(int tokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, (long) tokens);
        return this;
    }

    public LangfuseGeneration promptName(String name) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_PROMPT_NAME, name);
        return this;
    }

    public LangfuseGeneration promptVersion(int version) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_PROMPT_VERSION, (long) version);
        return this;
    }

    public LangfuseGeneration metadata(String key, String value) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_METADATA + "." + key, value);
        return this;
    }

    public LangfuseGeneration level(String level) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, level);
        return this;
    }

    public LangfuseGeneration statusMessage(String message) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
        return this;
    }

    public LangfusePromptHelper prompt(Object langfuseClient, String promptName) {
        try {
            Class.forName("com.langfuse.client.LangfuseClient");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "langfuse-java dependency is required for prompt compilation. "
                    + "Add com.langfuse:langfuse-java to your classpath.");
        }
        return new LangfusePromptHelper(langfuseClient, promptName, this);
    }

    public void recordException(Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        span.setStatus(StatusCode.ERROR, message);
        span.recordException(t);
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, "ERROR");
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
    }

    public Span getSpan() {
        return span;
    }

    public void end() {
        close();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanable.clean();
        }
    }
}
