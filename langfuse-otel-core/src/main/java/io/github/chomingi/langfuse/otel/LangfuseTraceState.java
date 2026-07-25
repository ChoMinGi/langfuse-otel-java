package io.github.chomingi.langfuse.otel;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Mutable trace-local carrier backed by immutable context snapshots.
 *
 * <p>The carrier lives in an OpenTelemetry {@code Context}, so captured contexts can cross
 * threads without relying on thread-local state. Updates are visible only to spans started
 * after the update completes.</p>
 */
final class LangfuseTraceState {

    private final AtomicReference<State> state;
    private final boolean legacyMutationAllowed;

    LangfuseTraceState(LangfuseTraceContext initial, boolean legacyMutationAllowed) {
        this.state = new AtomicReference<>(new State(initial, true));
        this.legacyMutationAllowed = legacyMutationAllowed;
    }

    LangfuseTraceContext snapshot() {
        return state.get().traceContext;
    }

    boolean isLegacyMutationAllowed() {
        return legacyMutationAllowed;
    }

    void userId(String userId) {
        update(current -> current.toBuilder().userId(userId).build());
    }

    void sessionId(String sessionId) {
        update(current -> current.toBuilder().sessionId(sessionId).build());
    }

    void tags(String... tags) {
        update(current -> current.toBuilder()
                .tags(tags == null ? null : Arrays.asList(tags))
                .build());
    }

    void environment(String environment) {
        update(current -> current.toBuilder().environment(environment).build());
    }

    void metadata(String key, String value) {
        update(current -> current.toBuilder().metadata(key, value).build());
    }

    void version(String version) {
        update(current -> current.toBuilder().version(version).build());
    }

    void release(String release) {
        update(current -> current.toBuilder().release(release).build());
    }

    void clearLegacyRouting() {
        update(current -> current.toBuilder()
                .userId(null)
                .sessionId(null)
                .tags((String[]) null)
                .environment(null)
                .build());
    }

    void freeze() {
        while (true) {
            State current = state.get();
            if (!current.open) return;
            if (state.compareAndSet(current, new State(current.traceContext, false))) return;
        }
    }

    private void update(UnaryOperator<LangfuseTraceContext> updater) {
        while (true) {
            State current = state.get();
            if (!current.open) return;
            LangfuseTraceContext updated = updater.apply(current.traceContext);
            if (state.compareAndSet(current, new State(updated, true))) return;
        }
    }

    private static final class State {
        private final LangfuseTraceContext traceContext;
        private final boolean open;

        private State(LangfuseTraceContext traceContext, boolean open) {
            this.traceContext = traceContext;
            this.open = open;
        }
    }
}
