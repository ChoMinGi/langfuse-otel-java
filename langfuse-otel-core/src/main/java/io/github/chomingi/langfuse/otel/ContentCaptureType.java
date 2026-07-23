package io.github.chomingi.langfuse.otel;

/** Identifies the automatic instrumentation content being processed. */
public enum ContentCaptureType {
    /** Model or operation input. */
    INPUT,
    /** Model or operation output. */
    OUTPUT
}
