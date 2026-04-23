package io.github.chomingi.langfuse.otel;

public final class LangfuseAttributes {

    private LangfuseAttributes() {}

    public static final String TRACE_NAME = "langfuse.trace.name";
    public static final String TRACE_USER_ID = "user.id";
    public static final String TRACE_SESSION_ID = "session.id";
    public static final String TRACE_TAGS = "langfuse.trace.tags";
    public static final String TRACE_PUBLIC = "langfuse.trace.public";
    public static final String TRACE_METADATA = "langfuse.trace.metadata";
    public static final String TRACE_INPUT = "langfuse.trace.input";
    public static final String TRACE_OUTPUT = "langfuse.trace.output";

    public static final String OBSERVATION_TYPE = "langfuse.observation.type";
    public static final String OBSERVATION_INPUT = "langfuse.observation.input";
    public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";
    public static final String OBSERVATION_METADATA = "langfuse.observation.metadata";
    public static final String OBSERVATION_LEVEL = "langfuse.observation.level";
    public static final String OBSERVATION_STATUS_MESSAGE = "langfuse.observation.status_message";
    public static final String OBSERVATION_MODEL = "langfuse.observation.model.name";
    public static final String OBSERVATION_MODEL_PARAMETERS = "langfuse.observation.model.parameters";
    public static final String OBSERVATION_USAGE_DETAILS = "langfuse.observation.usage_details";
    public static final String OBSERVATION_COST_DETAILS = "langfuse.observation.cost_details";
    public static final String OBSERVATION_PROMPT_NAME = "langfuse.observation.prompt.name";
    public static final String OBSERVATION_PROMPT_VERSION = "langfuse.observation.prompt.version";
    public static final String OBSERVATION_COMPLETION_START_TIME = "langfuse.observation.completion_start_time";

    public static final String ENVIRONMENT = "langfuse.environment";
    public static final String RELEASE = "langfuse.release";
    public static final String VERSION = "langfuse.version";

    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    public static final String GEN_AI_SYSTEM = "gen_ai.system";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    public static final String GEN_AI_REQUEST_TEMPERATURE = "gen_ai.request.temperature";
    public static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
    public static final String GEN_AI_REQUEST_TOP_P = "gen_ai.request.top_p";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String GEN_AI_USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";
}
