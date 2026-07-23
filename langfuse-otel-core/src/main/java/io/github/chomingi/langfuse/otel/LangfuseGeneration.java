package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

/**
 * Represents an LLM generation (model invocation). Tracks model, input/output, tokens, and metadata.
 * Created via {@code trace.generation("name")} or directly with a Tracer for AOP use cases.
 *
 * <p>The generation is a synchronous, thread-bound scope. Close it on the thread where it was
 * created and in reverse creation order relative to other Langfuse spans.</p>
 */
public class LangfuseGeneration extends AbstractLangfuseSpan {

    /**
     * Starts a generation and makes it current.
     *
     * @param tracer tracer used to start the span
     * @param name generation name
     */
    public LangfuseGeneration(Tracer tracer, String name) {
        super(tracer.spanBuilder(name)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "chat")
                .startSpan(), name);
    }

    /**
     * Records the GenAI operation name.
     *
     * @param operationName operation name
     * @return this generation
     */
    public LangfuseGeneration operationName(String operationName) {
        span.setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, operationName);
        return this;
    }

    /**
     * Records the requested model.
     *
     * @param model model name
     * @return this generation
     */
    public LangfuseGeneration model(String model) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, model);
        return this;
    }

    /**
     * Records the model reported by the response.
     *
     * @param model model name
     * @return this generation
     */
    public LangfuseGeneration responseModel(String model) {
        span.setAttribute(LangfuseAttributes.GEN_AI_RESPONSE_MODEL, model);
        return this;
    }

    /**
     * Records the model provider or system.
     *
     * @param system system name
     * @return this generation
     */
    public LangfuseGeneration system(String system) {
        span.setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, system);
        return this;
    }

    /**
     * Records the requested temperature.
     *
     * @param temperature temperature value
     * @return this generation
     */
    public LangfuseGeneration temperature(double temperature) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TEMPERATURE, temperature);
        return this;
    }

    /**
     * Records the requested maximum token count.
     *
     * @param maxTokens maximum tokens
     * @return this generation
     */
    public LangfuseGeneration maxTokens(int maxTokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MAX_TOKENS, (long) maxTokens);
        return this;
    }

    /**
     * Records the requested top-p value.
     *
     * @param topP top-p value
     * @return this generation
     */
    public LangfuseGeneration topP(double topP) {
        span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TOP_P, topP);
        return this;
    }

    /**
     * Records input directly, without applying the automatic capture policy.
     *
     * @param input input value
     * @return this generation
     */
    public LangfuseGeneration input(Object input) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, String.valueOf(input));
        return this;
    }

    /**
     * Records output directly, without applying the automatic capture policy.
     *
     * @param output output value
     * @return this generation
     */
    public LangfuseGeneration output(Object output) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, String.valueOf(output));
        return this;
    }

    /**
     * Records input token usage.
     *
     * @param tokens input tokens
     * @return this generation
     */
    public LangfuseGeneration inputTokens(int tokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) tokens);
        return this;
    }

    /**
     * Records output token usage.
     *
     * @param tokens output tokens
     * @return this generation
     */
    public LangfuseGeneration outputTokens(int tokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, (long) tokens);
        return this;
    }

    /**
     * Records total token usage.
     *
     * @param tokens total tokens
     * @return this generation
     */
    public LangfuseGeneration totalTokens(int tokens) {
        span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, (long) tokens);
        return this;
    }

    /**
     * Links the generation to a Langfuse prompt.
     *
     * @param name prompt name
     * @return this generation
     */
    public LangfuseGeneration promptName(String name) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_PROMPT_NAME, name);
        return this;
    }

    /**
     * Records the Langfuse prompt version.
     *
     * @param version prompt version
     * @return this generation
     */
    public LangfuseGeneration promptVersion(int version) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_PROMPT_VERSION, (long) version);
        return this;
    }

    /**
     * Records observation metadata.
     *
     * @param key metadata key
     * @param value metadata value
     * @return this generation
     */
    public LangfuseGeneration metadata(String key, String value) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_METADATA + "." + key, value);
        return this;
    }

    /**
     * Records the observation level.
     *
     * @param level level value
     * @return this generation
     */
    public LangfuseGeneration level(String level) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, level);
        return this;
    }

    /**
     * Records the observation status message.
     *
     * @param message status message
     * @return this generation
     */
    public LangfuseGeneration statusMessage(String message) {
        span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
        return this;
    }

    /**
     * Creates a text prompt helper linked to this generation.
     *
     * @param langfuseClient a {@code com.langfuse.client.LangfuseClient}
     * @param promptName prompt to fetch
     * @return a prompt helper
     * @throws IllegalStateException if the optional Langfuse client dependency is absent
     * @throws ClassCastException if {@code langfuseClient} is not a Langfuse client
     */
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
}
