package io.github.chomingi.langfuse.otel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable request metadata propagated with OpenTelemetry or reactive context.
 *
 * <p>This object deliberately contains only trace-wide Langfuse metadata. Prompt and response
 * content must not be placed in context because context can cross component boundaries.</p>
 */
public final class LangfuseTraceContext {

    private final String userId;
    private final String sessionId;
    private final List<String> tags;
    private final String environment;
    private final String traceName;
    private final Map<String, String> metadata;
    private final String version;
    private final String release;

    private LangfuseTraceContext(Builder builder) {
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.tags = Collections.unmodifiableList(new ArrayList<>(builder.tags));
        this.environment = builder.environment;
        this.traceName = builder.traceName;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        this.version = builder.version;
        this.release = builder.release;
    }

    /**
     * Creates a request metadata builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    Builder toBuilder() {
        return new Builder()
                .userId(userId)
                .sessionId(sessionId)
                .tags(tags)
                .environment(environment)
                .traceName(traceName)
                .metadata(metadata)
                .version(version)
                .release(release);
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

    /**
     * Returns the trace name propagated to relevant observations.
     *
     * @return the trace name, or {@code null}
     */
    public String getTraceName() {
        return traceName;
    }

    /**
     * Returns immutable trace metadata.
     *
     * @return trace metadata, never {@code null}
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Returns the trace or observation version.
     *
     * @return the version, or {@code null}
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the release identifier.
     *
     * @return the release identifier, or {@code null}
     */
    public String getRelease() {
        return release;
    }

    /** Builds immutable request metadata. */
    public static final class Builder {
        private String userId;
        private String sessionId;
        private List<String> tags = Collections.emptyList();
        private String environment;
        private String traceName;
        private Map<String, String> metadata = Collections.emptyMap();
        private String version;
        private String release;

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
         * Sets the trace name propagated to relevant observations.
         *
         * @param traceName trace name; may be {@code null}
         * @return this builder
         */
        public Builder traceName(String traceName) {
            this.traceName = traceName;
            return this;
        }

        /**
         * Adds or replaces one trace metadata entry.
         *
         * @param key metadata key
         * @param value metadata value
         * @return this builder
         * @throws NullPointerException if {@code key} or {@code value} is {@code null}
         */
        public Builder metadata(String key, String value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            Map<String, String> updated = new LinkedHashMap<>(metadata);
            updated.put(key, value);
            this.metadata = updated;
            return this;
        }

        /**
         * Replaces trace metadata.
         *
         * @param metadata metadata entries; {@code null} clears them
         * @return this builder
         */
        public Builder metadata(Map<String, String> metadata) {
            if (metadata == null) {
                this.metadata = Collections.emptyMap();
                return this;
            }
            Map<String, String> copy = new LinkedHashMap<>();
            metadata.forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "metadata key"),
                    Objects.requireNonNull(value, "metadata value")));
            this.metadata = copy;
            return this;
        }

        /**
         * Sets the trace or observation version.
         *
         * @param version version; may be {@code null}
         * @return this builder
         */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the release identifier.
         *
         * @param release release identifier; may be {@code null}
         * @return this builder
         */
        public Builder release(String release) {
            this.release = release;
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
