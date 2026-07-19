package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Context bridge for callback-native LangChain4j streaming providers.
 *
 * <p>The LangChain4j model SPI does not expose a provider executor or pass listener attributes to
 * {@code StreamingChatModel.doChat}. The tracing wrapper therefore makes a unique invocation
 * context current while calling {@code doChat}. A provider that schedules raw executor work can
 * capture that invocation at its scheduling boundary with {@link #wrap(Runnable)} or
 * {@link #taskWrapping(Executor)}. If submission itself happens after {@code doChat} returns, a
 * provider can retain a terminal-aware {@link Snapshot} obtained from {@link #capture()}.</p>
 */
public final class LangChain4jStreamingContext {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jStreamingContext.class);
    private static final ContextKey<State> STATE_KEY =
            ContextKey.named("langfuse-langchain4j-streaming-state");
    private static final Object OTEL_CONTEXT_ATTRIBUTE = new Object();
    private static final Object TRACE_CONTEXT_ATTRIBUTE = new Object();

    private LangChain4jStreamingContext() {
    }

    /**
     * Returns the OpenTelemetry context that is current at this provider scheduling boundary.
     *
     * @return the current context
     */
    public static Context context() {
        return Context.current();
    }

    /**
     * Returns immutable Langfuse metadata from the current provider scheduling boundary.
     *
     * @return the current metadata, or {@code null} when none is present
     */
    public static LangfuseTraceContext traceContext() {
        return LangfuseContext.from(Context.current());
    }

    /**
     * Returns the context placed in LangChain4j listener attributes. On request this is the caller
     * parent context; on response or error it is the wrapper invocation context.
     *
     * <p>The returned context is intended for synchronous inspection during the listener callback.
     * It does not enforce the invocation's terminal guard when made current directly. Deferred
     * work should use {@link #capture(Map)} and the snapshot's task or executor wrappers instead.</p>
     *
     * @param listenerAttributes attributes supplied to a LangChain4j model listener
     * @return the stored context, or {@code null} when unavailable
     */
    public static Context context(Map<Object, Object> listenerAttributes) {
        if (listenerAttributes == null) return null;
        Object value = listenerAttributes.get(OTEL_CONTEXT_ATTRIBUTE);
        return value instanceof Context ? (Context) value : null;
    }

    /**
     * Returns immutable Langfuse metadata placed in LangChain4j listener attributes.
     *
     * @param listenerAttributes attributes supplied to a LangChain4j model listener
     * @return the stored metadata, or {@code null} when unavailable
     */
    public static LangfuseTraceContext traceContext(Map<Object, Object> listenerAttributes) {
        if (listenerAttributes == null) return null;
        Object value = listenerAttributes.get(TRACE_CONTEXT_ATTRIBUTE);
        return value instanceof LangfuseTraceContext ? (LangfuseTraceContext) value : null;
    }

    /**
     * Captures the current provider invocation for work that may be submitted later.
     *
     * <p>A snapshot captured under the tracing wrapper is bound to that invocation's lifetime.
     * A wrapped execution admitted after the invocation has ended still runs, but does not restore
     * the ended observation. An execution admitted before terminal cleanup is already in flight
     * and finishes with its captured context. A snapshot captured outside the wrapper behaves like
     * a regular fixed OpenTelemetry context snapshot.</p>
     *
     * @return a reusable, thread-safe context snapshot
     */
    public static Snapshot capture() {
        return snapshot(Context.current(), null);
    }

    /**
     * Captures the context stored in LangChain4j listener attributes.
     *
     * @param listenerAttributes attributes supplied to a LangChain4j model listener
     * @return a reusable, thread-safe context snapshot, or {@code null} when no context was stored
     */
    public static Snapshot capture(Map<Object, Object> listenerAttributes) {
        Context storedContext = context(listenerAttributes);
        if (storedContext == null) return null;
        return snapshot(storedContext, traceContext(listenerAttributes));
    }

    /**
     * Captures the current wrapper invocation and restores it when {@code task} runs.
     *
     * @param task task submitted by the provider
     * @return a context-restoring task
     * @throws IllegalArgumentException when {@code task} is {@code null}
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        return capture().wrap(task);
    }

    /**
     * Captures the current wrapper invocation and restores it when {@code task} runs.
     *
     * @param <T> task result type
     * @param task task submitted by the provider
     * @return a context-restoring task
     * @throws IllegalArgumentException when {@code task} is {@code null}
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        return capture().wrap(task);
    }

    /**
     * Wraps each task submitted to {@code executor} with its submission-time context.
     *
     * @param executor provider executor
     * @return a context-propagating executor
     * @throws IllegalArgumentException when {@code executor} is {@code null}
     */
    public static Executor taskWrapping(Executor executor) {
        if (executor == null) throw new IllegalArgumentException("executor must not be null");
        return task -> executor.execute(capture().wrap(task));
    }

    /**
     * Wraps each task submitted to {@code executor} with its submission-time context.
     *
     * @param executor provider executor service
     * @return a context-propagating executor service
     * @throws IllegalArgumentException when {@code executor} is {@code null}
     */
    public static ExecutorService taskWrapping(ExecutorService executor) {
        if (executor == null) throw new IllegalArgumentException("executor must not be null");
        return new ContextExecutorService(executor, CurrentTaskContext.INSTANCE);
    }

    /**
     * Wraps each task submitted to {@code executor} while preserving scheduling capabilities.
     *
     * @param executor provider scheduled executor service
     * @return a context-propagating scheduled executor service
     * @throws IllegalArgumentException when {@code executor} is {@code null}
     */
    public static ScheduledExecutorService taskWrapping(ScheduledExecutorService executor) {
        if (executor == null) throw new IllegalArgumentException("executor must not be null");
        return new ContextScheduledExecutorService(executor, CurrentTaskContext.INSTANCE);
    }

    private static Snapshot snapshot(Context context, LangfuseTraceContext traceContext) {
        State state = null;
        LangfuseTraceContext metadata = traceContext;
        try {
            state = context.get(STATE_KEY);
            if (metadata == null) metadata = LangfuseContext.from(context);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not inspect the LangChain4j streaming context snapshot", failure);
        }
        return new Snapshot(context, state, metadata);
    }

    static Context storeState(Context context, State state) {
        return context.with(STATE_KEY, state);
    }

    static State currentState() {
        return Context.current().get(STATE_KEY);
    }

    static void putCurrentListenerAttributes(Map<Object, Object> attributes) {
        if (attributes == null) return;
        try {
            attributes.put(OTEL_CONTEXT_ATTRIBUTE, Context.current());
            LangfuseTraceContext traceContext = LangfuseContext.current();
            if (traceContext != null) attributes.put(TRACE_CONTEXT_ATTRIBUTE, traceContext);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not add caller context to LangChain4j listener attributes", failure);
        }
    }

    static void putListenerAttributes(Map<Object, Object> attributes, State state) {
        if (attributes == null || state == null) return;
        try {
            if (state.context() != null) attributes.put(OTEL_CONTEXT_ATTRIBUTE, state.context());
            if (state.traceContext() != null) attributes.put(TRACE_CONTEXT_ATTRIBUTE, state.traceContext());
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not add invocation context to LangChain4j listener attributes", failure);
        }
    }

    interface State {
        Context context();

        LangfuseTraceContext traceContext();

        boolean isActive();

        boolean tryStartTask();
    }

    /**
     * Reusable context captured from one LangChain4j provider invocation.
     *
     * <p>The snapshot does not retain an open {@link Scope}. Each wrapped task opens and closes a
     * short-lived scope on its executing thread. Instances are safe to reuse concurrently.</p>
     */
    public static final class Snapshot {
        private final Context context;
        private final State state;
        private final LangfuseTraceContext traceContext;

        private Snapshot(Context context, State state, LangfuseTraceContext traceContext) {
            this.context = context;
            this.state = state;
            this.traceContext = traceContext;
        }

        /**
         * Returns the captured immutable Langfuse metadata, if present.
         *
         * @return the captured metadata, or {@code null} when none was present
         */
        public LangfuseTraceContext traceContext() {
            return traceContext;
        }

        /**
         * Returns whether this snapshot belongs to an instrumented streaming invocation.
         *
         * @return {@code true} when terminal cleanup bounds this snapshot
         */
        public boolean isInvocationBound() {
            return state != null;
        }

        /**
         * Returns whether this snapshot may still restore its captured context.
         * Non-invocation snapshots remain active like regular OpenTelemetry context snapshots.
         *
         * @return {@code true} while the captured context may be restored
         */
        public boolean isActive() {
            return state == null || state.isActive();
        }

        /**
         * Captures this snapshot around each execution of {@code task}.
         *
         * @param task task to execute
         * @return a context-restoring task
         * @throws IllegalArgumentException when {@code task} is {@code null}
         */
        public Runnable wrap(Runnable task) {
            if (task == null) throw new IllegalArgumentException("task must not be null");
            return () -> runWithContext(task);
        }

        /**
         * Captures this snapshot around each execution of {@code task}.
         *
         * @param <T> task result type
         * @param task task to execute
         * @return a context-restoring task
         * @throws IllegalArgumentException when {@code task} is {@code null}
         */
        public <T> Callable<T> wrap(Callable<T> task) {
            if (task == null) throw new IllegalArgumentException("task must not be null");
            return () -> callWithContext(task);
        }

        /**
         * Returns an executor which always uses this snapshot.
         *
         * @param executor provider executor
         * @return a fixed-snapshot executor
         * @throws IllegalArgumentException when {@code executor} is {@code null}
         */
        public Executor taskWrapping(Executor executor) {
            if (executor == null) throw new IllegalArgumentException("executor must not be null");
            return task -> executor.execute(wrap(task));
        }

        /**
         * Returns an executor service which always uses this snapshot.
         *
         * @param executor provider executor service
         * @return a fixed-snapshot executor service
         * @throws IllegalArgumentException when {@code executor} is {@code null}
         */
        public ExecutorService taskWrapping(ExecutorService executor) {
            if (executor == null) throw new IllegalArgumentException("executor must not be null");
            return new ContextExecutorService(executor, new SnapshotTaskContext(this));
        }

        /**
         * Returns a scheduled executor service which always uses this snapshot.
         *
         * @param executor provider scheduled executor service
         * @return a fixed-snapshot scheduled executor service
         * @throws IllegalArgumentException when {@code executor} is {@code null}
         */
        public ScheduledExecutorService taskWrapping(ScheduledExecutorService executor) {
            if (executor == null) throw new IllegalArgumentException("executor must not be null");
            return new ContextScheduledExecutorService(executor, new SnapshotTaskContext(this));
        }

        private void runWithContext(Runnable task) {
            if (!tryStartTask()) {
                task.run();
                return;
            }
            Scope scope = makeCurrent();
            if (scope == null) {
                task.run();
                return;
            }
            try {
                task.run();
            } finally {
                closeQuietly(scope);
            }
        }

        private <T> T callWithContext(Callable<T> task) throws Exception {
            if (!tryStartTask()) return task.call();
            Scope scope = makeCurrent();
            if (scope == null) return task.call();
            try {
                return task.call();
            } finally {
                closeQuietly(scope);
            }
        }

        private boolean tryStartTask() {
            return state == null || state.tryStartTask();
        }

        private Scope makeCurrent() {
            try {
                return context.makeCurrent();
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                log.debug("Could not restore a LangChain4j streaming context snapshot", failure);
                return null;
            }
        }
    }

    private interface TaskContext {
        Runnable wrap(Runnable task);

        <T> Callable<T> wrap(Callable<T> task);
    }

    private static final class SnapshotTaskContext implements TaskContext {
        private final Snapshot snapshot;

        private SnapshotTaskContext(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Runnable wrap(Runnable task) {
            return snapshot.wrap(task);
        }

        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
            return snapshot.wrap(task);
        }
    }

    private enum CurrentTaskContext implements TaskContext {
        INSTANCE;

        @Override
        public Runnable wrap(Runnable task) {
            return capture().wrap(task);
        }

        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
            return capture().wrap(task);
        }
    }

    private static class ContextExecutorService extends AbstractExecutorService {
        protected final ExecutorService delegate;
        protected final TaskContext taskContext;

        private ContextExecutorService(ExecutorService delegate, TaskContext taskContext) {
            this.delegate = delegate;
            this.taskContext = taskContext;
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            if (command == null) throw new IllegalArgumentException("command must not be null");
            delegate.execute(taskContext.wrap(command));
        }
    }

    private static final class ContextScheduledExecutorService extends ContextExecutorService
            implements ScheduledExecutorService {
        private final ScheduledExecutorService scheduledDelegate;

        private ContextScheduledExecutorService(ScheduledExecutorService delegate,
                                                TaskContext taskContext) {
            super(delegate, taskContext);
            this.scheduledDelegate = delegate;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (command == null) throw new IllegalArgumentException("command must not be null");
            return scheduledDelegate.schedule(taskContext.wrap(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            if (callable == null) throw new IllegalArgumentException("callable must not be null");
            return scheduledDelegate.schedule(taskContext.wrap(callable), delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                      long initialDelay,
                                                      long period,
                                                      TimeUnit unit) {
            if (command == null) throw new IllegalArgumentException("command must not be null");
            return scheduledDelegate.scheduleAtFixedRate(
                    taskContext.wrap(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                         long initialDelay,
                                                         long delay,
                                                         TimeUnit unit) {
            if (command == null) throw new IllegalArgumentException("command must not be null");
            return scheduledDelegate.scheduleWithFixedDelay(
                    taskContext.wrap(command), initialDelay, delay, unit);
        }
    }

    private static void closeQuietly(Scope scope) {
        if (scope == null) return;
        try {
            scope.close();
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not close a LangChain4j streaming context snapshot", failure);
        }
    }
}
