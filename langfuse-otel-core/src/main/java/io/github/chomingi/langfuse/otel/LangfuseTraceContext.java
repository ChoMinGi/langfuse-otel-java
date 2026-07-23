package io.github.chomingi.langfuse.otel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable request metadata propagated with OpenTelemetry or reactive context.
 *
 * <p>This object deliberately contains only Langfuse routing metadata. Prompt and response
 * content must not be placed in context because context can cross component boundaries.</p>
 */
public final class LangfuseTraceContext {

    private final String userId;
    private final String sessionId;
    private final List<String> tags;
    private final String environment;

    private LangfuseTraceContext(Builder builder) {
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.tags = Collections.unmodifiableList(new ArrayList<>(builder.tags));
        this.environment = builder.environment;
    }

    /**
     * Creates a request metadata builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the user identifier.
     *
     * @return the user identifier, or {@code null}
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns the session identifier.
     *
     * @return the session identifier, or {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the immutable trace tags.
     *
     * @return trace tags, never {@code null}
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * Returns the environment.
     *
     * @return the environment, or {@code null}
     */
    public String getEnvironment() {
        return environment;
    }

    /** Builds immutable request metadata. */
    public static final class Builder {
        private String userId;
        private String sessionId;
        private List<String> tags = Collections.emptyList();
        private String environment;

        private Builder() {}

        /**
         * Sets the user identifier.
         *
         * @param userId user identifier; may be {@code null}
         * @return this builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the session identifier.
         *
         * @param sessionId session identifier; may be {@code null}
         * @return this builder
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets trace tags.
         *
         * @param tags tags; {@code null} clears them
         * @return this builder
         */
        public Builder tags(String... tags) {
            this.tags = tags == null ? Collections.emptyList() : Arrays.asList(tags);
            return this;
        }

        /**
         * Sets trace tags.
         *
         * @param tags tags; {@code null} clears them
         * @return this builder
         */
        public Builder tags(List<String> tags) {
            this.tags = tags == null ? Collections.emptyList() : tags;
            return this;
        }

        /**
         * Sets the environment.
         *
         * @param environment environment name; may be {@code null}
         * @return this builder
         */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Builds an immutable metadata value.
         *
         * @return the configured metadata
         */
        public LangfuseTraceContext build() {
            return new LangfuseTraceContext(this);
        }
    }
}
