package io.github.chomingi.langfuse.otel;

/** Identifies an exception detail being processed for automatic instrumentation. */
public enum ExceptionCaptureType {
    MESSAGE,
    STACK_TRACE
}
