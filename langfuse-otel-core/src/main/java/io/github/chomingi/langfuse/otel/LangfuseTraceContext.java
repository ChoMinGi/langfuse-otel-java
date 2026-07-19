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

    public static Builder builder() {
        return new Builder();
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getEnvironment() {
        return environment;
    }

    public static final class Builder {
        private String userId;
        private String sessionId;
        private List<String> tags = Collections.emptyList();
        private String environment;

        private Builder() {}

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder tags(String... tags) {
            this.tags = tags == null ? Collections.emptyList() : Arrays.asList(tags);
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags == null ? Collections.emptyList() : tags;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public LangfuseTraceContext build() {
            return new LangfuseTraceContext(this);
        }
    }
}
