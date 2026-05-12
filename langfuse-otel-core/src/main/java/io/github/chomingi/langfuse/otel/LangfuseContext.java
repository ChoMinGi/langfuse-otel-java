package io.github.chomingi.langfuse.otel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ThreadLocal-based context for propagating userId, sessionId, tags, and environment to all traces and spans.
 * Set values in a filter/interceptor; they are automatically applied to spans via {@code LangfuseContextSpanProcessor}.
 * Call {@link #clear()} in a finally block to avoid leaking across requests.
 * For async propagation, copy values to the target thread manually or use a framework-specific context propagator.
 */
public final class LangfuseContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> TAGS = new ThreadLocal<>();
    private static final ThreadLocal<String> ENVIRONMENT = new ThreadLocal<>();

    private LangfuseContext() {}

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void setTags(String... tags) {
        TAGS.set(Arrays.asList(tags));
    }

    public static List<String> getTags() {
        List<String> tags = TAGS.get();
        return tags != null ? tags : Collections.emptyList();
    }

    public static void setEnvironment(String environment) {
        ENVIRONMENT.set(environment);
    }

    public static String getEnvironment() {
        return ENVIRONMENT.get();
    }

    public static void clear() {
        USER_ID.remove();
        SESSION_ID.remove();
        TAGS.remove();
        ENVIRONMENT.remove();
    }
}
