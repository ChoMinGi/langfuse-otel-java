package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReactorSchedulerContextPropagationTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void closedLeaseDoesNotRestoreAnEndedContextForAnAlreadyDecoratedTask() {
        Span parent = otel.getOpenTelemetry().getTracer("scheduler-lease-test")
                .spanBuilder("lease-parent")
                .startSpan();
        ReactorSchedulerContextPropagation.Lease lease =
                ReactorSchedulerContextPropagation.acquire(Context.root().with(parent));
        AtomicReference<SpanContext> observed = new AtomicReference<>();
        try {
            Runnable decorated;
            try (Scope ignored = lease.context().makeCurrent()) {
                decorated = Schedulers.onSchedule(
                        () -> observed.set(Span.current().getSpanContext()));
            }

            lease.close();
            parent.end();
            decorated.run();

            assertThat(observed.get().isValid()).isFalse();
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        } finally {
            lease.close();
            parent.end();
        }
    }

    @Test
    void concurrentLeasesUseTheirOwnContextAndCloseIndependently() {
        Span firstParent = otel.getOpenTelemetry().getTracer("scheduler-lease-test")
                .spanBuilder("first-lease-parent")
                .startSpan();
        Span secondParent = otel.getOpenTelemetry().getTracer("scheduler-lease-test")
                .spanBuilder("second-lease-parent")
                .startSpan();
        ReactorSchedulerContextPropagation.Lease firstLease =
                ReactorSchedulerContextPropagation.acquire(Context.root().with(firstParent));
        ReactorSchedulerContextPropagation.Lease secondLease =
                ReactorSchedulerContextPropagation.acquire(Context.root().with(secondParent));
        AtomicReference<String> firstObserved = new AtomicReference<>();
        AtomicReference<String> secondObserved = new AtomicReference<>();
        try {
            Runnable firstTask;
            Runnable secondTask;

            try (Scope ignored = firstLease.context().makeCurrent()) {
                firstTask = Schedulers.onSchedule(
                        () -> firstObserved.set(Span.current().getSpanContext().getSpanId()));
            }
            try (Scope ignored = secondLease.context().makeCurrent()) {
                secondTask = Schedulers.onSchedule(
                        () -> secondObserved.set(Span.current().getSpanContext().getSpanId()));
            }

            firstLease.close();
            firstTask.run();
            secondTask.run();

            assertThat(firstObserved.get()).isEqualTo(SpanContext.getInvalid().getSpanId());
            assertThat(secondObserved.get()).isEqualTo(secondParent.getSpanContext().getSpanId());
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isEqualTo(1);

            secondLease.close();
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        } finally {
            firstLease.close();
            secondLease.close();
            firstParent.end();
            secondParent.end();
        }
    }

    @Test
    void closingOurLastLeaseDoesNotRemoveAnExternalSchedulerHook() {
        String externalHookKey = getClass().getName() + ".external";
        AtomicInteger externalRuns = new AtomicInteger();
        Schedulers.onScheduleHook(externalHookKey, task -> () -> {
            externalRuns.incrementAndGet();
            task.run();
        });
        Span parent = otel.getOpenTelemetry().getTracer("scheduler-lease-test")
                .spanBuilder("external-hook-parent")
                .startSpan();
        ReactorSchedulerContextPropagation.Lease lease =
                ReactorSchedulerContextPropagation.acquire(Context.root().with(parent));
        try {
            AtomicReference<String> observedParent = new AtomicReference<>();
            Runnable instrumentedTask;
            try (Scope ignored = lease.context().makeCurrent()) {
                instrumentedTask = Schedulers.onSchedule(() -> observedParent.set(
                        Span.current().getSpanContext().getSpanId()));
            }
            instrumentedTask.run();

            lease.close();
            Runnable externalOnlyTask = Schedulers.onSchedule(() -> {
            });
            externalOnlyTask.run();

            assertThat(observedParent.get()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(externalRuns.get()).isEqualTo(2);
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
        } finally {
            lease.close();
            parent.end();
            Schedulers.resetOnScheduleHook(externalHookKey);
        }
    }

    @Test
    void nonFatalContextLookupFailureDoesNotBreakSchedulerDecoration() {
        ReactorSchedulerContextPropagation.Lease lease =
                ReactorSchedulerContextPropagation.acquire(Context.root());
        Context failingContext = new Context() {
            @Override
            public <V> V get(io.opentelemetry.context.ContextKey<V> key) {
                throw new IllegalStateException("context lookup unavailable");
            }

            @Override
            public <V> Context with(io.opentelemetry.context.ContextKey<V> key, V value) {
                return this;
            }
        };
        AtomicBoolean executed = new AtomicBoolean();
        try {
            Runnable decorated;
            try (Scope ignored = failingContext.makeCurrent()) {
                decorated = Schedulers.onSchedule(() -> executed.set(true));
            }

            decorated.run();

            assertThat(executed).isTrue();
        } finally {
            lease.close();
        }
        assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
    }

    @Test
    void bridgeReleasesSchedulerLeaseOnErrorAndCancellation() {
        Span parent = otel.getOpenTelemetry().getTracer("scheduler-lease-test")
                .spanBuilder("bridge-parent")
                .startSpan();
        Context parentContext = Context.root().with(parent);

        try {
            StepVerifier.create(ReactorSpanContextBridge.bridge(
                            Flux.error(new IllegalStateException("boom")), parentContext, null))
                    .expectErrorMessage("boom")
                    .verify();
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();

            StepVerifier.create(ReactorSpanContextBridge.bridge(
                            Flux.never(), parentContext, null))
                    .thenCancel()
                    .verify();
        } finally {
            parent.end();
        }
        assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void fatalSourceSubscriptionStillReleasesTheSchedulerLease() {
        Span parent = otel.getOpenTelemetry().getTracer("scheduler-lease-test")
                .spanBuilder("fatal-bridge-parent")
                .startSpan();
        Flux<Object> fatalSource = Flux.defer(() -> {
            throw new LinkageError("fatal source subscribe");
        });

        try {
            assertThatThrownBy(() -> ReactorSpanContextBridge.bridge(
                            fatalSource, Context.root().with(parent), null)
                    .blockLast())
                    .isInstanceOf(LinkageError.class)
                    .hasMessage("fatal source subscribe");
        } finally {
            parent.end();
        }

        assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void rawPublisherSignalsRestoreDownstreamContextWithoutAReactorScheduler() throws Exception {
        Span parent = otel.getOpenTelemetry().getTracer("raw-signal-test")
                .spanBuilder("raw-signal-parent")
                .startSpan();
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("raw-signal-user")
                .sessionId("raw-signal-session")
                .build();
        Context parentContext = LangfuseContext.storeIn(
                Context.root().with(parent), traceContext);
        AtomicReference<SpanContext> sourceBeforeSignal = new AtomicReference<>();
        AtomicReference<SpanContext> downstreamSignal = new AtomicReference<>();
        AtomicReference<LangfuseTraceContext> downstreamMetadata = new AtomicReference<>();
        AtomicReference<SpanContext> sourceAfterTerminal = new AtomicReference<>();
        CountDownLatch sourceReturned = new CountDownLatch(1);
        Publisher<String> rawPublisher = subscriber -> subscriber.onSubscribe(new Subscription() {
            private final AtomicBoolean requested = new AtomicBoolean();

            @Override
            public void request(long amount) {
                if (!requested.compareAndSet(false, true)) {
                    return;
                }
                Thread rawThread = new Thread(() -> {
                    sourceBeforeSignal.set(Span.current().getSpanContext());
                    subscriber.onNext("raw-value");
                    subscriber.onComplete();
                    sourceAfterTerminal.set(Span.current().getSpanContext());
                    sourceReturned.countDown();
                }, "raw-publisher-signal-test");
                rawThread.start();
            }

            @Override
            public void cancel() {
            }
        });

        try {
            String result = ReactorSpanContextBridge.bridge(
                            Flux.from(rawPublisher), parentContext, traceContext)
                    .doOnNext(value -> {
                        downstreamSignal.set(Span.current().getSpanContext());
                        downstreamMetadata.set(LangfuseContext.current());
                        Span child = otel.getOpenTelemetry().getTracer("raw-signal-test")
                                .spanBuilder("raw-signal-downstream-child")
                                .startSpan();
                        child.end();
                    })
                    .blockLast();

            assertThat(result).isEqualTo("raw-value");
            assertThat(sourceReturned.await(5, TimeUnit.SECONDS)).isTrue();
            SpanData child = otel.getSpans().stream()
                    .filter(span -> span.getName().equals("raw-signal-downstream-child"))
                    .findFirst().orElseThrow();
            assertThat(sourceBeforeSignal.get().isValid()).isFalse();
            assertThat(downstreamSignal.get().getSpanId())
                    .isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(downstreamMetadata.get()).isSameAs(traceContext);
            assertThat(sourceAfterTerminal.get().isValid()).isFalse();
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
        } finally {
            parent.end();
        }
    }

    @Test
    void requestAndCancelAreScopedButLateSignalsDoNotRestoreAClosedLease() {
        Span parent = otel.getOpenTelemetry().getTracer("raw-signal-test")
                .spanBuilder("request-cancel-parent")
                .startSpan();
        ReactorSchedulerContextPropagation.Lease lease =
                ReactorSchedulerContextPropagation.acquire(Context.root().with(parent));
        AtomicReference<SpanContext> requestContext = new AtomicReference<>();
        AtomicReference<SpanContext> cancelContext = new AtomicReference<>();
        AtomicReference<SpanContext> lateSignalContext = new AtomicReference<>();
        AtomicReference<Subscription> downstreamSubscription = new AtomicReference<>();
        Subscription upstream = new Subscription() {
            @Override
            public void request(long amount) {
                requestContext.set(Span.current().getSpanContext());
            }

            @Override
            public void cancel() {
                cancelContext.set(Span.current().getSpanContext());
            }
        };
        Subscriber<String> downstream = new CoreSubscriber<String>() {
            @Override
            public reactor.util.context.Context currentContext() {
                return reactor.util.context.Context.empty();
            }

            @Override
            public void onSubscribe(Subscription subscription) {
                downstreamSubscription.set(subscription);
                subscription.request(1);
            }

            @Override
            public void onNext(String value) {
                lateSignalContext.set(Span.current().getSpanContext());
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onComplete() {
            }
        };

        try {
            Subscriber<? super String> scoped =
                    ReactorSchedulerContextPropagation.scopeSubscriber(downstream, lease);
            scoped.onSubscribe(upstream);
            downstreamSubscription.get().cancel();
            scoped.onNext("late");

            assertThat(requestContext.get().getSpanId())
                    .isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(cancelContext.get().getSpanId())
                    .isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(lateSignalContext.get().isValid()).isFalse();
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
        } finally {
            lease.close();
            parent.end();
        }
    }

    @Test
    void terminalReturnClosesTheLeaseBeforeASequentialLateSignal() {
        Span parent = otel.getOpenTelemetry().getTracer("raw-signal-test")
                .spanBuilder("terminal-late-parent")
                .startSpan();
        ReactorSchedulerContextPropagation.Lease lease =
                ReactorSchedulerContextPropagation.acquire(Context.root().with(parent));
        AtomicReference<SpanContext> terminalContext = new AtomicReference<>();
        AtomicReference<SpanContext> lateSignalContext = new AtomicReference<>();
        CoreSubscriber<String> downstream = new CoreSubscriber<String>() {
            @Override
            public reactor.util.context.Context currentContext() {
                return reactor.util.context.Context.empty();
            }

            @Override
            public void onSubscribe(Subscription subscription) {
            }

            @Override
            public void onNext(String value) {
                lateSignalContext.set(Span.current().getSpanContext());
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onComplete() {
                terminalContext.set(Span.current().getSpanContext());
            }
        };

        try {
            Subscriber<? super String> scoped =
                    ReactorSchedulerContextPropagation.scopeSubscriber(downstream, lease);
            scoped.onSubscribe(new Subscription() {
                @Override
                public void request(long amount) {
                }

                @Override
                public void cancel() {
                }
            });
            scoped.onComplete();
            scoped.onNext("late");

            assertThat(terminalContext.get().getSpanId())
                    .isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(lateSignalContext.get().isValid()).isFalse();
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
        } finally {
            lease.close();
            parent.end();
        }
    }

    @Test
    void reentrantCancelDefersLeaseCloseUntilTheActiveOnNextReturns() {
        Span parent = otel.getOpenTelemetry().getTracer("raw-signal-test")
                .spanBuilder("reentrant-cancel-parent")
                .startSpan();
        ReactorSchedulerContextPropagation.Lease lease =
                ReactorSchedulerContextPropagation.acquire(Context.root().with(parent));
        AtomicReference<Subscription> downstreamSubscription = new AtomicReference<>();
        AtomicBoolean activeAfterNestedCancel = new AtomicBoolean(false);
        AtomicReference<SpanContext> cancelContext = new AtomicReference<>();
        CoreSubscriber<String> downstream = new CoreSubscriber<String>() {
            @Override
            public reactor.util.context.Context currentContext() {
                return reactor.util.context.Context.empty();
            }

            @Override
            public void onSubscribe(Subscription subscription) {
                downstreamSubscription.set(subscription);
            }

            @Override
            public void onNext(String value) {
                downstreamSubscription.get().cancel();
                activeAfterNestedCancel.set(lease.isActive());
                assertThat(Span.current().getSpanContext().getSpanId())
                        .isEqualTo(parent.getSpanContext().getSpanId());
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onComplete() {
            }
        };

        try {
            Subscriber<? super String> scoped =
                    ReactorSchedulerContextPropagation.scopeSubscriber(downstream, lease);
            scoped.onSubscribe(new Subscription() {
                @Override
                public void request(long amount) {
                }

                @Override
                public void cancel() {
                    cancelContext.set(Span.current().getSpanContext());
                }
            });
            scoped.onNext("first-and-only");

            assertThat(activeAfterNestedCancel).isTrue();
            assertThat(cancelContext.get().getSpanId())
                    .isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(lease.isActive()).isFalse();
            assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
        } finally {
            lease.close();
            parent.end();
        }
    }

    @Test
    void publicRawPublisherBridgeIsolatesConcurrentSubscriptionContexts() {
        Publisher<String> rawPublisher = subscriber -> subscriber.onSubscribe(new Subscription() {
            private final AtomicBoolean requested = new AtomicBoolean();

            @Override
            public void request(long amount) {
                if (!requested.compareAndSet(false, true)) {
                    return;
                }
                LangfuseTraceContext requestContext = LangfuseContext.current();
                String userId = requestContext == null ? null : requestContext.getUserId();
                Thread rawThread = new Thread(() -> {
                    subscriber.onNext(userId);
                    subscriber.onComplete();
                }, "public-raw-context-" + userId);
                rawThread.start();
            }

            @Override
            public void cancel() {
            }
        });
        Flux<String> shared = Flux.from(ReactorContextPropagation.wrap(rawPublisher))
                .map(requestUser -> LangfuseContext.current().getUserId() + ":" + requestUser);
        LangfuseTraceContext first = LangfuseTraceContext.builder()
                .userId("raw-concurrent-a")
                .build();
        LangfuseTraceContext second = LangfuseTraceContext.builder()
                .userId("raw-concurrent-b")
                .build();

        List<String> values = Flux.merge(
                        shared.contextWrite(context -> context.put(
                                        LangfuseContext.reactorContextKey(), first))
                                .subscribeOn(Schedulers.parallel()),
                        shared.contextWrite(context -> context.put(
                                        LangfuseContext.reactorContextKey(), second))
                                .subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertThat(values).containsExactlyInAnyOrder(
                "raw-concurrent-a:raw-concurrent-a",
                "raw-concurrent-b:raw-concurrent-b");
        awaitNoRegisteredLeases();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void fallbackAndForcedCleanupRespectSignalLifecycleOwnership() {
        ReactorSchedulerContextPropagation.Lease normalLease =
                ReactorSchedulerContextPropagation.acquire(Context.root());
        CoreSubscriber<String> downstream = noOpCoreSubscriber();
        Subscriber<? super String> normalScoped =
                ReactorSchedulerContextPropagation.scopeSubscriber(downstream, normalLease);

        ReactorSchedulerContextPropagation.closeFallbackForBridge(normalLease);
        assertThat(normalLease.isActive()).isTrue();
        normalScoped.onComplete();
        assertThat(normalLease.isActive()).isFalse();

        ReactorSchedulerContextPropagation.Lease failedSubscriptionLease =
                ReactorSchedulerContextPropagation.acquire(Context.root());
        ReactorSchedulerContextPropagation.scopeSubscriber(
                downstream, failedSubscriptionLease);
        ReactorSchedulerContextPropagation.closeForBridge(failedSubscriptionLease);

        assertThat(failedSubscriptionLease.isActive()).isFalse();
        assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
    }

    private static void awaitNoRegisteredLeases() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && ReactorSchedulerContextPropagation.registeredLeaseCount() != 0) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(ReactorSchedulerContextPropagation.registeredLeaseCount()).isZero();
    }

    private static CoreSubscriber<String> noOpCoreSubscriber() {
        return new CoreSubscriber<String>() {
            @Override
            public reactor.util.context.Context currentContext() {
                return reactor.util.context.Context.empty();
            }

            @Override
            public void onSubscribe(Subscription subscription) {
            }

            @Override
            public void onNext(String value) {
            }

            @Override
            public void onError(Throwable failure) {
            }

            @Override
            public void onComplete() {
            }
        };
    }
}
