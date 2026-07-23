package io.github.chomingi.langfuse.otel;

/** OpenTelemetry attribute names understood by Langfuse. */
public final class LangfuseAttributes {

    private LangfuseAttributes() {}

    /** {@value}. */
    public static final String TRACE_NAME = "langfuse.trace.name";
    /** {@value}. */
    public static final String TRACE_USER_ID = "user.id";
    /** {@value}. */
    public static final String TRACE_SESSION_ID = "session.id";
    /** {@value}. */
    public static final String TRACE_TAGS = "langfuse.trace.tags";
    /** {@value}. */
    public static final String TRACE_PUBLIC = "langfuse.trace.public";
    /** {@value}. */
    public static final String TRACE_METADATA = "langfuse.trace.metadata";
    /** {@value}. */
    public static final String TRACE_INPUT = "langfuse.trace.input";
    /** {@value}. */
    public static final String TRACE_OUTPUT = "langfuse.trace.output";

    /** {@value}. */
    public static final String OBSERVATION_TYPE = "langfuse.observation.type";
    /** {@value}. */
    public static final String OBSERVATION_INPUT = "langfuse.observation.input";
    /** {@value}. */
    public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";
    /** {@value}. */
    public static final String OBSERVATION_METADATA = "langfuse.observation.metadata";
    /** {@value}. */
    public static final String OBSERVATION_LEVEL = "langfuse.observation.level";
    /** {@value}. */
    public static final String OBSERVATION_STATUS_MESSAGE = "langfuse.observation.status_message";
    /** {@value}. */
    public static final String OBSERVATION_MODEL = "langfuse.observation.model.name";
    /** {@value}. */
    public static final String OBSERVATION_MODEL_PARAMETERS = "langfuse.observation.model.parameters";
    /** {@value}. */
    public static final String OBSERVATION_USAGE_DETAILS = "langfuse.observation.usage_details";
    /** {@value}. */
    public static final String OBSERVATION_COST_DETAILS = "langfuse.observation.cost_details";
    /** {@value}. */
    public static final String OBSERVATION_PROMPT_NAME = "langfuse.observation.prompt.name";
    /** {@value}. */
    public static final String OBSERVATION_PROMPT_VERSION = "langfuse.observation.prompt.version";
    /** {@value}. */
    public static final String OBSERVATION_COMPLETION_START_TIME = "langfuse.observation.completion_start_time";

    /** {@value}. */
    public static final String ENVIRONMENT = "langfuse.environment";
    /** {@value}. */
    public static final String RELEASE = "langfuse.release";
    /** {@value}. */
    public static final String VERSION = "langfuse.version";

    /** {@value}. */
    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    /** {@value}. */
    public static final String GEN_AI_SYSTEM = "gen_ai.system";
    /** {@value}. */
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    /** {@value}. */
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    /** {@value}. */
    public static final String GEN_AI_REQUEST_TEMPERATURE = "gen_ai.request.temperature";
    /** {@value}. */
    public static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
    /** {@value}. */
    public static final String GEN_AI_REQUEST_TOP_P = "gen_ai.request.top_p";
    /** {@value}. */
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    /** {@value}. */
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    /** {@value}. */
    public static final String GEN_AI_USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";
}
