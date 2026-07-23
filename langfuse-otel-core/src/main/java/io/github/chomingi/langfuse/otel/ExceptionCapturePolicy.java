package io.github.chomingi.langfuse.otel;

import java.util.Objects;

/**
 * Immutable safety policy for exception details recorded by automatic instrumentation.
 * Exception type is always retained; message and stack trace are independent opt-ins.
 */
public final class ExceptionCapturePolicy {

    /** Finite default applied independently to message and stack trace after redaction. */
    public static final int DEFAULT_MAX_LENGTH = 8_192;

    private static final ExceptionRedactor IDENTITY_REDACTOR = (type, content) -> content;

    private final boolean messageCaptureEnabled;
    private final boolean stackTraceCaptureEnabled;
    private final int maxLength;
    private final ExceptionRedactor redactor;

    private ExceptionCapturePolicy(Builder builder) {
        this.messageCaptureEnabled = builder.messageCaptureEnabled;
        this.stackTraceCaptureEnabled = builder.stackTraceCaptureEnabled;
        this.maxLength = builder.maxLength;
        this.redactor = builder.redactor;
    }

    /**
     * Returns the production-safe default, which records only the exception type.
     *
     * @return a type-only policy
     */
    public static ExceptionCapturePolicy typeOnly() {
        return builder().build();
    }

    /**
     * Enables message and stack-trace capture with the finite default length limit.
     *
     * @return a policy that captures messages and stack traces
     */
    public static ExceptionCapturePolicy captureAll() {
        return builder()
                .captureMessage(true)
                .captureStackTrace(true)
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
     * Returns whether exception messages may be recorded.
     *
     * @return {@code true} when message capture is enabled
     */
    public boolean isMessageCaptureEnabled() {
        return messageCaptureEnabled;
    }

    /**
     * Returns whether stack traces may be recorded.
     *
     * @return {@code true} when stack-trace capture is enabled
     */
    public boolean isStackTraceCaptureEnabled() {
        return stackTraceCaptureEnabled;
    }

    /**
     * Returns the post-redaction limit for each detail in UTF-16 code units.
     *
     * @return the configured limit
     */
    public int getMaxLength() {
        return maxLength;
    }

    String capture(ExceptionCaptureType type, String value) {
        Objects.requireNonNull(type, "type");
        if (value == null || !isEnabled(type)) {
            return null;
        }

        try {
            String redacted = redactor.redact(type, value);
            if (redacted == null) {
                return null;
            }
            return truncateWithoutSplittingSurrogatePair(redacted);
        } catch (Throwable ignored) {
            // Instrumentation and user-provided redactors must never affect host application behavior.
            return null;
        }
    }

    private boolean isEnabled(ExceptionCaptureType type) {
        return type == ExceptionCaptureType.MESSAGE
                ? messageCaptureEnabled
                : stackTraceCaptureEnabled;
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

    /** Builds an exception capture policy. */
    public static final class Builder {

        private boolean messageCaptureEnabled;
        private boolean stackTraceCaptureEnabled;
        private int maxLength = DEFAULT_MAX_LENGTH;
        private ExceptionRedactor redactor = IDENTITY_REDACTOR;

        private Builder() {}

        /**
         * Enables or disables exception message capture.
         *
         * @param enabled whether messages may be recorded
         * @return this builder
         */
        public Builder captureMessage(boolean enabled) {
            this.messageCaptureEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables stack-trace capture.
         *
         * @param enabled whether stack traces may be recorded
         * @return this builder
         */
        public Builder captureStackTrace(boolean enabled) {
            this.stackTraceCaptureEnabled = enabled;
            return this;
        }

        /**
         * Sets the maximum number of UTF-16 code units retained per captured exception detail.
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
        public Builder redactor(ExceptionRedactor redactor) {
            this.redactor = Objects.requireNonNull(redactor, "redactor");
            return this;
        }

        /**
         * Builds an immutable policy.
         *
         * @return the configured policy
         */
        public ExceptionCapturePolicy build() {
            return new ExceptionCapturePolicy(this);
        }
    }
}
