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

    /**
     * Returns the production-safe default, which records metadata but no input or output.
     *
     * @return a metadata-only policy
     */
    public static ContentCapturePolicy metadataOnly() {
        return builder().build();
    }

    /**
     * Enables input and output capture with the finite default length limit.
     *
     * @return a policy that captures input and output
     */
    public static ContentCapturePolicy captureAll() {
        return builder()
                .captureInput(true)
                .captureOutput(true)
                .build();
    }

    /**
     * Creates a policy builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether automatic instrumentation may record input.
     *
     * @return {@code true} when input capture is enabled
     */
    public boolean isInputCaptureEnabled() {
        return inputCaptureEnabled;
    }

    /**
     * Returns whether automatic instrumentation may record output.
     *
     * @return {@code true} when output capture is enabled
     */
    public boolean isOutputCaptureEnabled() {
        return outputCaptureEnabled;
    }

    /**
     * Returns the post-redaction capture limit in UTF-16 code units.
     *
     * @return the configured limit
     */
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

    /** Builds a content capture policy. */
    public static final class Builder {

        private boolean inputCaptureEnabled;
        private boolean outputCaptureEnabled;
        private int maxLength = DEFAULT_MAX_LENGTH;
        private ContentRedactor redactor = IDENTITY_REDACTOR;

        private Builder() {}

        /**
         * Enables or disables automatic input capture.
         *
         * @param enabled whether input may be recorded
         * @return this builder
         */
        public Builder captureInput(boolean enabled) {
            this.inputCaptureEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables automatic output capture.
         *
         * @param enabled whether output may be recorded
         * @return this builder
         */
        public Builder captureOutput(boolean enabled) {
            this.outputCaptureEnabled = enabled;
            return this;
        }

        /**
         * Sets the maximum number of UTF-16 code units retained after redaction.
         *
         * @param maxLength a positive capture limit
         * @return this builder
         * @throws IllegalArgumentException if {@code maxLength} is not positive
         */
        public Builder maxLength(int maxLength) {
            if (maxLength <= 0) {
                throw new IllegalArgumentException("maxLength must be greater than zero");
            }
            this.maxLength = maxLength;
            return this;
        }

        /**
         * Sets the redactor invoked before truncation.
         *
         * @param redactor a thread-safe redactor
         * @return this builder
         * @throws NullPointerException if {@code redactor} is {@code null}
         */
        public Builder redactor(ContentRedactor redactor) {
            this.redactor = Objects.requireNonNull(redactor, "redactor");
            return this;
        }

        /**
         * Builds an immutable policy.
         *
         * @return the configured policy
         */
        public ContentCapturePolicy build() {
            return new ContentCapturePolicy(this);
        }
    }
}
