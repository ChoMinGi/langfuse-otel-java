package io.github.chomingi.langfuse.otel.spring;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs a keyed Reactor scheduler hook only while instrumented subscriptions are active.
 * Each decorated task checks its subscription lease at execution time so delayed or periodic
 * work cannot restore an observation context after that observation has terminated.
 */
final class ReactorSchedulerContextPropagation {

    private static final String HOOK_KEY = ReactorSchedulerContextPropagation.class.getName();
    private static final ContextKey<Lease> LEASE_KEY = ContextKey.named(HOOK_KEY + ".lease");
    private static final Object REGISTRATION_MONITOR = new Object();

    private static int registeredLeases;

    private ReactorSchedulerContextPropagation() {
    }

    static Lease acquire(Context context) {
        Context baseContext = context == null ? Context.current() : context;
        boolean registered = false;
        synchronized (REGISTRATION_MONITOR) {
            try {
                if (registeredLeases == 0) {
                    Schedulers.onScheduleHook(HOOK_KEY, ReactorSchedulerContextPropagation::decorate);
                }
                registeredLeases++;
                registered = true;
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                // Scheduler propagation is best-effort. The source still subscribes normally.
            }
        }
        try {
            return new Lease(baseContext, registered);
        } catch (Throwable failure) {
            if (registered) {
                unregister();
            }
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            return new Lease(baseContext, false);
        }
    }

    /** Reflection-only adapter for the optional Reactor annotation bridge. */
    static Object acquireForBridge(Context context) {
        return acquire(context);
    }

    /** Reflection-only adapter for the optional Reactor annotation bridge. */
    static Context contextForBridge(Object lease) {
        return lease instanceof Lease ? ((Lease) lease).context() : Context.current();
    }

    /** Reflection-only adapter for the optional Reactor annotation bridge. */
    static void closeForBridge(Object lease) {
        if (lease instanceof Lease) {
            ((Lease) lease).close();
        }
    }

    /** Reflection-only fallback used after a normal signal-managed lifecycle. */
    static void closeFallbackForBridge(Object lease) {
        if (lease instanceof Lease) {
            ((Lease) lease).closeFallback();
        }
    }

    /** Reflection-only adapter for the optional Reactor annotation bridge. */
    static Subscriber<?> scopeSubscriberForBridge(Subscriber<?> subscriber, Object lease) {
        return lease instanceof Lease
                ? scopeSubscriber(subscriber, (Lease) lease)
                : subscriber;
    }

    static <T> Subscriber<? super T> scopeSubscriber(Subscriber<? super T> subscriber,
                                                      Lease lease) {
        if (subscriber == null || lease == null) {
            return subscriber;
        }
        try {
            Subscriber<? super T> scoped = new ContextPropagatingSubscriber<>(
                    Operators.toCoreSubscriber(subscriber), lease);
            lease.markSignalLifecycleOwner();
            return scoped;
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            return subscriber;
        }
    }

    private static Runnable decorate(Runnable task) {
        try {
            Context captured = Context.current();
            Lease lease = captured.get(LEASE_KEY);
            if (lease == null || !lease.isActive()) {
                return task;
            }
            return () -> lease.runWith(captured, task);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            return task;
        }
    }

    private static void unregister() {
        synchronized (REGISTRATION_MONITOR) {
            if (registeredLeases == 0) {
                return;
            }
            registeredLeases--;
            if (registeredLeases == 0) {
                try {
                    Schedulers.resetOnScheduleHook(HOOK_KEY);
                } catch (Throwable failure) {
                    InstrumentationFailureSupport.rethrowIfFatal(failure);
                    // A nonfatal Reactor cleanup failure must not replace an application signal.
                }
            }
        }
    }

    static int registeredLeaseCount() {
        synchronized (REGISTRATION_MONITOR) {
            return registeredLeases;
        }
    }

    static final class Lease implements AutoCloseable {

        private final Context context;
        private final boolean registered;
        private final AtomicBoolean active;
        private final AtomicBoolean signalLifecycleOwner = new AtomicBoolean(false);

        private Lease(Context baseContext, boolean registered) {
            this.registered = registered;
            // Signal propagation remains useful even if installing the optional global
            // scheduler hook failed. Registration and lease lifetime are separate concerns.
            this.active = new AtomicBoolean(true);
            this.context = registered ? baseContext.with(LEASE_KEY, this) : baseContext;
        }

        Context context() {
            return context;
        }

        boolean isActive() {
            return active.get();
        }

        boolean hasSignalLifecycleOwner() {
            return signalLifecycleOwner.get();
        }

        private void markSignalLifecycleOwner() {
            signalLifecycleOwner.set(true);
        }

        void closeFallback() {
            if (!signalLifecycleOwner.get()) {
                close();
            }
        }

        private void runWith(Context captured, Runnable task) {
            if (!active.get()) {
                task.run();
                return;
            }

            Scope scope;
            try {
                scope = captured.makeCurrent();
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                task.run();
                return;
            }

            if (!active.get()) {
                closeScopeQuietly(scope);
                task.run();
                return;
            }

            try {
                task.run();
            } finally {
                closeScopeQuietly(scope);
            }
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false) && registered) {
                unregister();
            }
        }
    }

    /**
     * Restores a lease at the Reactive Streams signal boundary. This covers publishers that
     * invoke their subscriber directly from a raw thread and therefore never pass through a
     * Reactor scheduler hook. The signal state is separate from the lease so a terminal/cancel
     * race cannot re-enter an ended context for a late signal.
     */
    private static final class ContextPropagatingSubscriber<T> implements CoreSubscriber<T> {

        private final CoreSubscriber<? super T> delegate;
        private final SignalState signalState;

        private ContextPropagatingSubscriber(CoreSubscriber<? super T> delegate, Lease lease) {
            this.delegate = delegate;
            this.signalState = new SignalState(lease);
        }

        @Override
        public reactor.util.context.Context currentContext() {
            return delegate.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            Subscription propagated = new ContextPropagatingSubscription(
                    subscription, signalState);
            signalState.runSignal(() -> delegate.onSubscribe(propagated));
        }

        @Override
        public void onNext(T value) {
            signalState.runSignal(() -> delegate.onNext(value));
        }

        @Override
        public void onError(Throwable failure) {
            signalState.runTerminal(() -> delegate.onError(failure));
        }

        @Override
        public void onComplete() {
            signalState.runTerminal(delegate::onComplete);
        }
    }

    private static final class ContextPropagatingSubscription implements Subscription {

        private final Subscription delegate;
        private final SignalState signalState;

        private ContextPropagatingSubscription(Subscription delegate,
                                               SignalState signalState) {
            this.delegate = delegate;
            this.signalState = signalState;
        }

        @Override
        public void request(long amount) {
            signalState.runSignal(() -> delegate.request(amount));
        }

        @Override
        public void cancel() {
            signalState.runTerminal(delegate::cancel);
        }
    }

    /**
     * Tracks concurrent boundaries without assuming that cancellation cannot happen reentrantly
     * from an {@code onNext} callback (as {@code Mono.from(Publisher)} commonly does). Closing is
     * delayed until every boundary that entered before termination has returned.
     */
    private static final class SignalState {

        private static final int TERMINATED = Integer.MIN_VALUE;
        private static final int ACTIVE_COUNT_MASK = Integer.MAX_VALUE;

        private final Lease lease;
        private final AtomicInteger state = new AtomicInteger();

        private SignalState(Lease lease) {
            this.lease = lease;
        }

        private void runSignal(Runnable signal) {
            if (!enter(false)) {
                signal.run();
                return;
            }
            try {
                lease.runWith(lease.context(), signal);
            } finally {
                exit();
            }
        }

        private void runTerminal(Runnable signal) {
            if (!enter(true)) {
                signal.run();
                return;
            }
            try {
                lease.runWith(lease.context(), signal);
            } finally {
                exit();
            }
        }

        private boolean enter(boolean terminal) {
            while (true) {
                int current = state.get();
                if ((current & TERMINATED) != 0
                        || (current & ACTIVE_COUNT_MASK) == ACTIVE_COUNT_MASK) {
                    return false;
                }
                int updated = current + 1;
                if (terminal) {
                    updated |= TERMINATED;
                }
                if (state.compareAndSet(current, updated)) {
                    return true;
                }
            }
        }

        private void exit() {
            int remaining = state.decrementAndGet();
            if (remaining == TERMINATED) {
                lease.close();
            }
        }
    }

    private static void closeScopeQuietly(Scope scope) {
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
        }
    }
}
