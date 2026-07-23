package io.github.chomingi.langfuse.otel;

/**
 * Redacts automatically captured model content before it is attached to a span.
 * Implementations may be invoked concurrently and should therefore be thread-safe.
 */
@FunctionalInterface
public interface ContentRedactor {

    /**
     * Returns redacted content, or {@code null} to discard it.
     * Exceptions are contained by the capture policy and never reach the host application.
     *
     * @param type the content direction
     * @param content the unredacted content
     * @return redacted content, or {@code null} to discard it
     * @throws Exception if redaction fails; the capture policy contains the exception
     */
    String redact(ContentCaptureType type, String content) throws Exception;
}
