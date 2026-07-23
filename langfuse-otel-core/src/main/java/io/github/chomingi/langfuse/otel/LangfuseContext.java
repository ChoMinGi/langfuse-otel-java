package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Context for propagating userId, sessionId, tags, and environment to Langfuse spans.
 *
 * <p>The setter API remains for synchronous and legacy integrations. New integrations should create an immutable
 * {@link LangfuseTraceContext} and propagate it with {@link #storeIn(Context, LangfuseTraceContext)}. Reactive
 * integrations can store the same value under {@link #reactorContextKey()} without relying on thread affinity.
 * Legacy mutations can override immutable metadata only inside {@link #makeCurrent(LangfuseTraceContext)}, which
 * provides the restoration boundary; mutations inside an unmanaged immutable OTel scope are ignored.</p>
 */
public final class LangfuseContext {

    private static final ContextKey<LangfuseTraceContext> OTEL_CONTEXT_KEY =
            ContextKey.named("langfuse-trace-context");
    private static final Object REACTOR_CONTEXT_KEY = new Object();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> TAGS = new ThreadLocal<>();
    private static final ThreadLocal<String> ENVIRONMENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> LEGACY_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> MANAGED_SCOPE = new ThreadLocal<>();

    private LangfuseContext() {}

    /**
     * Returns the stable key used to store {@link LangfuseTraceContext} in a Reactor Context.
     *
     * @return the Reactor context key
     */
    public static Object reactorContextKey() {
        return REACTOR_CONTEXT_KEY;
    }

    /**
     * Adds Langfuse request metadata to an OpenTelemetry context.
     *
     * @param context source context
     * @param traceContext metadata to store
     * @return a context containing the metadata
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public static Context storeIn(Context context, LangfuseTraceContext traceContext) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (traceContext == null) {
            throw new IllegalArgumentException("traceContext must not be null");
        }
        return context.with(OTEL_CONTEXT_KEY, traceContext);
    }

    /**
     * Returns metadata stored in an OpenTelemetry context.
     *
     * @param context context to read; may be {@code null}
     * @return stored metadata, or {@code null} when absent
     */
    public static LangfuseTraceContext from(Context context) {
        return context == null ? null : context.get(OTEL_CONTEXT_KEY);
    }

    /**
     * Makes immutable Langfuse metadata current for synchronous work and restores the previous values on close.
     *
     * @param traceContext metadata to make current
     * @return a scope that restores the previous metadata
     * @throws IllegalArgumentException if {@code traceContext} is {@code null}
     */
    public static Scope makeCurrent(LangfuseTraceContext traceContext) {
        if (traceContext == null) {
            throw new IllegalArgumentException("traceContext must not be null");
        }

        String previousUserId = USER_ID.get();
        String previousSessionId = SESSION_ID.get();
        List<String> previousTags = TAGS.get();
        String previousEnvironment = ENVIRONMENT.get();
        Boolean previousLegacyOverride = LEGACY_OVERRIDE.get();
        Boolean previousManagedScope = MANAGED_SCOPE.get();

        setLocal(traceContext);
        LEGACY_OVERRIDE.set(Boolean.TRUE);
        MANAGED_SCOPE.set(Boolean.TRUE);
        Scope otelScope;
        try {
            otelScope = storeIn(Context.current(), traceContext).makeCurrent();
        } catch (RuntimeException | Error failure) {
            restoreLegacy(previousUserId, previousSessionId, previousTags, previousEnvironment,
                    previousLegacyOverride, previousManagedScope);
            throw failure;
        }
        return restoringScope(otelScope,
                () -> restoreLegacy(previousUserId, previousSessionId, previousTags, previousEnvironment,
                        previousLegacyOverride, previousManagedScope));
    }

    static Scope restoringScope(Scope otelScope, Runnable restoreAction) {
        return new Scope() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                try {
                    otelScope.close();
                } finally {
                    restoreAction.run();
                }
            }
        };
    }

    /**
     * Sets the legacy thread-local user identifier. This mutation is ignored while immutable
     * metadata installed outside {@link #makeCurrent(LangfuseTraceContext)} is current.
     *
     * @param userId user identifier; may be {@code null}
     */
    public static void setUserId(String userId) {
        if (!legacyMutationAllowed()) return;
        markLegacyOverride();
        USER_ID.set(userId);
    }

    /**
     * Returns the current user identifier.
     *
     * @return the user identifier, or {@code null}
     */
    public static String getUserId() {
        if (hasLegacyOverride()) return USER_ID.get();
        LangfuseTraceContext traceContext = from(Context.current());
        return traceContext != null ? traceContext.getUserId() : USER_ID.get();
    }

    /**
     * Sets the legacy thread-local session identifier. This mutation is ignored while immutable
     * metadata installed outside {@link #makeCurrent(LangfuseTraceContext)} is current.
     *
     * @param sessionId session identifier; may be {@code null}
     */
    public static void setSessionId(String sessionId) {
        if (!legacyMutationAllowed()) return;
        markLegacyOverride();
        SESSION_ID.set(sessionId);
    }

    /**
     * Returns the current session identifier.
     *
     * @return the session identifier, or {@code null}
     */
    public static String getSessionId() {
        if (hasLegacyOverride()) return SESSION_ID.get();
        LangfuseTraceContext traceContext = from(Context.current());
        return traceContext != null ? traceContext.getSessionId() : SESSION_ID.get();
    }

    /**
     * Sets the legacy thread-local trace tags. This mutation is ignored while immutable metadata
     * installed outside {@link #makeCurrent(LangfuseTraceContext)} is current.
     *
     * @param tags trace tags; must not be {@code null}
     * @throws NullPointerException if {@code tags} is {@code null}
     */
    public static void setTags(String... tags) {
        if (!legacyMutationAllowed()) return;
        markLegacyOverride();
        TAGS.set(Arrays.asList(tags));
    }

    /**
     * Returns the current trace tags.
     *
     * @return trace tags, never {@code null}
     */
    public static List<String> getTags() {
        if (hasLegacyOverride()) return getLocalTags();
        LangfuseTraceContext traceContext = from(Context.current());
        if (traceContext != null) return traceContext.getTags();
        List<String> tags = TAGS.get();
        return tags != null ? tags : Collections.emptyList();
    }

    /**
     * Sets the legacy thread-local environment. This mutation is ignored while immutable metadata
     * installed outside {@link #makeCurrent(LangfuseTraceContext)} is current.
     *
     * @param environment environment name; may be {@code null}
     */
    public static void setEnvironment(String environment) {
        if (!legacyMutationAllowed()) return;
        markLegacyOverride();
        ENVIRONMENT.set(environment);
    }

    /**
     * Returns the current environment.
     *
     * @return the environment, or {@code null}
     */
    public static String getEnvironment() {
        if (hasLegacyOverride()) return ENVIRONMENT.get();
        LangfuseTraceContext traceContext = from(Context.current());
        return traceContext != null ? traceContext.getEnvironment() : ENVIRONMENT.get();
    }

    /**
     * Returns an immutable snapshot of the current OTel or legacy thread-local values.
     *
     * @return current request metadata
     */
    public static LangfuseTraceContext current() {
        if (hasLegacyOverride()) return legacyCurrent();
        LangfuseTraceContext traceContext = from(Context.current());
        if (traceContext != null) return traceContext;
        return legacyCurrent();
    }

    static LangfuseTraceContext legacyCurrent() {
        return LangfuseTraceContext.builder()
                .userId(USER_ID.get())
                .sessionId(SESSION_ID.get())
                .tags(getLocalTags())
                .environment(ENVIRONMENT.get())
                .build();
    }

    /**
     * Clears legacy thread-local metadata. This mutation is ignored while immutable metadata
     * installed outside {@link #makeCurrent(LangfuseTraceContext)} is current.
     */
    public static void clear() {
        if (!legacyMutationAllowed()) return;
        markLegacyOverride();
        USER_ID.remove();
        SESSION_ID.remove();
        TAGS.remove();
        ENVIRONMENT.remove();
    }

    static boolean hasLegacyOverride() {
        return Boolean.TRUE.equals(LEGACY_OVERRIDE.get());
    }

    /**
     * Applies immutable Langfuse request metadata directly to a library-created span.
     *
     * @param span destination span
     * @param traceContext metadata to apply; {@code null} is ignored
     * @throws IllegalArgumentException if {@code span} is {@code null}
     */
    public static void applyTo(Span span, LangfuseTraceContext traceContext) {
        if (span == null) {
            throw new IllegalArgumentException("span must not be null");
        }
        if (traceContext == null) return;
        if (traceContext.getUserId() != null) {
            span.setAttribute(LangfuseAttributes.TRACE_USER_ID, traceContext.getUserId());
        }
        if (traceContext.getSessionId() != null) {
            span.setAttribute(LangfuseAttributes.TRACE_SESSION_ID, traceContext.getSessionId());
        }
        if (!traceContext.getTags().isEmpty()) {
            span.setAttribute(AttributeKey.stringArrayKey(LangfuseAttributes.TRACE_TAGS), traceContext.getTags());
        }
        if (traceContext.getEnvironment() != null) {
            span.setAttribute(LangfuseAttributes.ENVIRONMENT, traceContext.getEnvironment());
        }
    }

    /**
     * Applies the current OTel or legacy Langfuse request metadata directly to a span.
     *
     * @param span destination span
     * @throws IllegalArgumentException if {@code span} is {@code null}
     */
    public static void applyTo(Span span) {
        applyTo(span, current());
    }

    static void applyTo(ReadWriteSpan span, LangfuseTraceContext traceContext) {
        if (traceContext == null) return;
        if (traceContext.getUserId() != null) {
            span.setAttribute(LangfuseAttributes.TRACE_USER_ID, traceContext.getUserId());
        }
        if (traceContext.getSessionId() != null) {
            span.setAttribute(LangfuseAttributes.TRACE_SESSION_ID, traceContext.getSessionId());
        }
        if (!traceContext.getTags().isEmpty()) {
            span.setAttribute(AttributeKey.stringArrayKey(LangfuseAttributes.TRACE_TAGS), traceContext.getTags());
        }
        if (traceContext.getEnvironment() != null) {
            span.setAttribute(LangfuseAttributes.ENVIRONMENT, traceContext.getEnvironment());
        }
    }

    private static void setLocal(LangfuseTraceContext traceContext) {
        restore(USER_ID, traceContext.getUserId());
        restore(SESSION_ID, traceContext.getSessionId());
        restore(TAGS, traceContext.getTags().isEmpty() ? null : traceContext.getTags());
        restore(ENVIRONMENT, traceContext.getEnvironment());
    }

    private static List<String> getLocalTags() {
        List<String> tags = TAGS.get();
        return tags == null ? Collections.emptyList() : tags;
    }

    private static void restoreLegacy(String userId, String sessionId, List<String> tags, String environment,
                                      Boolean legacyOverride, Boolean managedScope) {
        restore(USER_ID, userId);
        restore(SESSION_ID, sessionId);
        restore(TAGS, tags);
        restore(ENVIRONMENT, environment);
        restore(LEGACY_OVERRIDE, legacyOverride);
        restore(MANAGED_SCOPE, managedScope);
    }

    private static void markLegacyOverride() {
        if (Boolean.TRUE.equals(MANAGED_SCOPE.get())) {
            LEGACY_OVERRIDE.set(Boolean.TRUE);
        } else {
            LEGACY_OVERRIDE.remove();
        }
    }

    private static boolean legacyMutationAllowed() {
        return from(Context.current()) == null || Boolean.TRUE.equals(MANAGED_SCOPE.get());
    }

    private static <T> void restore(ThreadLocal<T> holder, T value) {
        if (value == null) holder.remove();
        else holder.set(value);
    }
}
