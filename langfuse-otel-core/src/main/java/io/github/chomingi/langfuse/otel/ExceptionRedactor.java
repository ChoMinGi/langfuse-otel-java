package io.github.chomingi.langfuse.otel;

/**
 * Redacts opt-in exception details before they are attached to a span.
 * Implementations may be invoked concurrently and should therefore be thread-safe.
 */
@FunctionalInterface
public interface ExceptionRedactor {

    /**
     * Returns redacted content, or {@code null} to discard it.
     * Exceptions are contained by the capture policy and never reach the host application.
     */
    String redact(ExceptionCaptureType type, String content) throws Exception;
}
