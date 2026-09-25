package io.github.chomingi.langfuse.otel;

/** Langfuse observation types. Types describe observations; they do not select an execution engine. */
public enum ObservationType {
    /** General operation. */
    SPAN,
    /** Model generation. */
    GENERATION,
    /** Discrete event. */
    EVENT,
    /** Embedding operation. */
    EMBEDDING,
    /** Agent operation. */
    AGENT,
    /** Tool operation. */
    TOOL,
    /** Workflow or chain. */
    CHAIN,
    /** Retrieval operation. */
    RETRIEVER,
    /** Guardrail operation. */
    GUARDRAIL,
    /** Evaluation operation. */
    EVALUATOR;

    String attributeValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
