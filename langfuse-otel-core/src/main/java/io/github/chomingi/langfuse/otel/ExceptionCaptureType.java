package io.github.chomingi.langfuse.otel;

/** Identifies an exception detail being processed for automatic instrumentation. */
public enum ExceptionCaptureType {
    /** Exception message. */
    MESSAGE,
    /** Rendered exception stack trace. */
    STACK_TRACE
}
