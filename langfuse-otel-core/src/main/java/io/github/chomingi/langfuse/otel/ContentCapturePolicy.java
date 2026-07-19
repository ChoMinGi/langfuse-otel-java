package io.github.chomingi.langfuse.otel;

import java.util.Objects;

/**
 * Immutable safety policy for content recorded by automatic instrumentation.
 * Manual fluent {@code input(...)} and {@code output(...)} calls are not governed by this policy.
 */
public final class ContentCapturePolicy {

    /** Finite default applied after redaction. */
    public static final int DEFAULT_MAX_LENGTH = 8_192;

    private static final ContentRedactor IDENTITY_REDACTOR = (type, content) -> content;

    private final boolean inputCaptureEnabled;
    private final boolean outputCaptureEnabled;
    private final int maxLength;
    private final ContentRedactor redactor;

    private ContentCapturePolicy(Builder builder) {
        this.inputCaptureEnabled = builder.inputCaptureEnabled;
        this.outputCaptureEnabled = builder.outputCaptureEnabled;
        this.maxLength = builder.maxLength;
        this.redactor = builder.redactor;
    }

    /** Returns the production-safe default, which records metadata but no input or output. */
    public static ContentCapturePolicy metadataOnly() {
        return builder().build();
    }

    /** Enables input and output capture with the finite default length limit. */
    public static ContentCapturePolicy captureAll() {
        return builder()
                .captureInput(true)
                .captureOutput(true)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isInputCaptureEnabled() {
        return inputCaptureEnabled;
    }

    public boolean isOutputCaptureEnabled() {
        return outputCaptureEnabled;
    }

    public int getMaxLength() {
        return maxLength;
    }

    String capture(ContentCaptureType type, Object value) {
        Objects.requireNonNull(type, "type");
        if (value == null || !isEnabled(type)) {
            return null;
        }

        try {
            String content = value instanceof String ? (String) value : String.valueOf(value);
            if (content == null) {
                return null;
            }

            String redacted = redactor.redact(type, content);
            if (redacted == null) {
                return null;
            }
            return truncateWithoutSplittingSurrogatePair(redacted);
        } catch (Throwable ignored) {
            // Instrumentation and user-provided redactors must never affect host application behavior.
            return null;
        }
    }

    private boolean isEnabled(ContentCaptureType type) {
        return type == ContentCaptureType.INPUT ? inputCaptureEnabled : outputCaptureEnabled;
    }

    private String truncateWithoutSplittingSurrogatePair(String content) {
        if (content.length() <= maxLength) {
            return content;
        }

        int endIndex = maxLength;
        if (Character.isHighSurrogate(content.charAt(endIndex - 1))
                && Character.isLowSurrogate(content.charAt(endIndex))) {
            endIndex--;
        }
        return content.substring(0, endIndex);
    }

    public static final class Builder {

        private boolean inputCaptureEnabled;
        private boolean outputCaptureEnabled;
        private int maxLength = DEFAULT_MAX_LENGTH;
        private ContentRedactor redactor = IDENTITY_REDACTOR;

        private Builder() {}

        public Builder captureInput(boolean enabled) {
            this.inputCaptureEnabled = enabled;
            return this;
        }

        public Builder captureOutput(boolean enabled) {
            this.outputCaptureEnabled = enabled;
            return this;
        }

        /** Sets the maximum number of UTF-16 code units retained after redaction. */
        public Builder maxLength(int maxLength) {
            if (maxLength <= 0) {
                throw new IllegalArgumentException("maxLength must be greater than zero");
            }
            this.maxLength = maxLength;
            return this;
        }

        public Builder redactor(ContentRedactor redactor) {
            this.redactor = Objects.requireNonNull(redactor, "redactor");
            return this;
        }

        public ContentCapturePolicy build() {
            return new ContentCapturePolicy(this);
        }
    }
}
