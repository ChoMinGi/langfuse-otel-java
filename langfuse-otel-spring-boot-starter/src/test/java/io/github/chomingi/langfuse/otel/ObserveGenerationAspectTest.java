package io.github.chomingi.langfuse.otel;

import io.github.chomingi.langfuse.otel.spring.ReactorContextPropagation;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGenerationAspect;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObserveGenerationAspectTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void annotationCapturesConfiguredMetadataAndOutput() {
        TestService proxy = proxy(new TestService());

        String result = proxy.summarize("hello");

        assertThat(result).isEqualTo("summary: hello");
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getName()).isEqualTo("summarize");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("openai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("summary: hello");
    }

    @Test
    void annotationCapturesExceptions() {
        TestService proxy = proxy(new TestService());

        assertThatThrownBy(proxy::fail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message")))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void completionStageKeepsTheOriginalIdentityAndEndsOnlyAfterSuccessfulCompletion() {
        TestService target = new TestService();
        TestService proxy = proxy(target);

        CompletionStage<String> result = proxy.delayed();

        assertThat(result).isSameAs(target.pending);
        assertThat(otel.getSpans()).isEmpty();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();

        target.pending.complete("future-result");

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getName()).isEqualTo("future-generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("future-result");
    }

    @Test
    void completionStageRecordsAnAsyncFailureAtCompletion() {
        TestService target = new TestService();
        TestService proxy = proxy(target);

        proxy.delayed();
        target.pending.completeExceptionally(new IllegalArgumentException("async boom"));

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message")))
                .isEqualTo(IllegalArgumentException.class.getName());
    }

    @Test
    void cancelledCompletionStageEndsWithoutReportingAnApplicationError() {
        TestService target = new TestService();
        TestService proxy = proxy(target);

        proxy.delayed();
        target.pending.cancel(false);

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isNull();
    }

    @Test
    void alreadyCompletedStageIsFinalizedBeforeReturningWithoutChangingIdentity() {
        TestService target = new TestService();
        target.pending.complete("already-done");
        TestService proxy = proxy(target);

        CompletionStage<String> result = proxy.delayed();

        assertThat(result).isSameAs(target.pending);
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getAttributes()
                .get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("already-done");
    }

    @Test
    void objectDeclaredSynchronousResultIsObservedNormally() {
        TestService proxy = proxy(new TestService());

        Object result = proxy.objectResult();

        assertThat(result).isEqualTo("object-result");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getName()).isEqualTo("object-generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("object-result");
    }

    @Test
    void objectDeclaredCompletionStageStillEndsAtActualCompletion() {
        TestService target = new TestService();
        TestService proxy = proxy(target);

        Object result = proxy.objectDelayed();

        assertThat(result).isSameAs(target.objectPending);
        assertThat(otel.getSpans()).isEmpty();

        target.objectPending.complete("object-future-result");

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getName()).isEqualTo("object-future-generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("object-future-result");
    }

    @Test
    void monoCreatesNoSpanUntilSubscriptionAndCreatesOneSpanPerSubscription() {
        TestService proxy = proxy(new TestService());

        Mono<String> result = proxy.reactive();

        assertThat(otel.getSpans()).isEmpty();
        assertThat(result.block()).isEqualTo("reactive-result");
        assertThat(result.block()).isEqualTo("reactive-result");

        assertThat(otel.getSpans()).filteredOn(span -> span.getName().equals("reactive-generation"))
                .hasSize(2);
    }

    @Test
    void monoObservationRemainsRecordingThroughDownstreamTerminalCallbacks() {
        TestService proxy = proxy(new TestService());
        AtomicBoolean recordingInDownstreamSuccess = new AtomicBoolean(false);

        String result = proxy.reactive()
                .doOnSuccess(value -> recordingInDownstreamSuccess.set(
                        Span.current().isRecording()))
                .block();

        assertThat(result).isEqualTo("reactive-result");
        assertThat(recordingInDownstreamSuccess).isTrue();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void customConcretePublisherIsReturnedUnchangedWithoutAnAssemblySpan() {
        TestService target = new TestService();
        TestService proxy = proxy(target);

        CustomPublisher result = proxy.customPublisher();

        assertThat(result).isSameAs(target.customPublisher);
        assertThat(otel.getSpans()).isEmpty();
        StepVerifier.create(result)
                .expectNext("custom-result")
                .verifyComplete();
        assertThat(otel.getSpans()).isEmpty();
    }

    @Test
    void reactiveSourceSubscriptionRunsUnderTheObservationAndCarriesRequestMetadata() {
        TestService proxy = proxy(new TestService());
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("reactive-user")
                .sessionId("reactive-session")
                .build();

        String value = proxy.reactiveWithChildSpan()
                .contextWrite(context -> context.put(LangfuseContext.reactorContextKey(), traceContext))
                .block();

        assertThat(value).isEqualTo("child-result");
        SpanData observation = otel.getSpans().stream()
                .filter(span -> span.getName().equals("reactive-parent"))
                .findFirst().orElseThrow();
        SpanData child = otel.getSpans().stream()
                .filter(span -> span.getName().equals("provider-child"))
                .findFirst().orElseThrow();
        assertThat(child.getParentSpanId()).isEqualTo(observation.getSpanId());
        assertThat(observation.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo("reactive-user");
        assertThat(observation.getAttributes().get(AttributeKey.stringKey("session.id")))
                .isEqualTo("reactive-session");
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void concurrentSubscriptionsKeepReactorMetadataIsolated() {
        TestService proxy = proxy(new TestService());
        Mono<String> shared = proxy.reactive();
        LangfuseTraceContext firstContext = LangfuseTraceContext.builder()
                .userId("reactive-user-a")
                .sessionId("reactive-session-a")
                .build();
        LangfuseTraceContext secondContext = LangfuseTraceContext.builder()
                .userId("reactive-user-b")
                .sessionId("reactive-session-b")
                .build();

        List<String> results = Flux.merge(
                        shared.contextWrite(context -> context.put(
                                        LangfuseContext.reactorContextKey(), firstContext))
                                .subscribeOn(Schedulers.parallel()),
                        shared.contextWrite(context -> context.put(
                                        LangfuseContext.reactorContextKey(), secondContext))
                                .subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertThat(results).containsExactlyInAnyOrder("reactive-result", "reactive-result");
        List<SpanData> observations = awaitSpansNamed("reactive-generation", 2);
        assertThat(observations)
                .extracting(span -> span.getAttributes().get(AttributeKey.stringKey("user.id")))
                .containsExactlyInAnyOrder("reactive-user-a", "reactive-user-b");
        assertThat(observations)
                .extracting(span -> span.getAttributes().get(AttributeKey.stringKey("session.id")))
                .containsExactlyInAnyOrder("reactive-session-a", "reactive-session-b");
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void subscriptionOtelContextsAreResolvedAndEnrichedIndependently() {
        TestService proxy = proxy(new TestService());
        Mono<String> shared = proxy.reactiveTraceContextMetadata();
        LangfuseTraceContext firstTraceContext = LangfuseTraceContext.builder()
                .userId("otel-subscription-user-a")
                .sessionId("otel-subscription-session-a")
                .build();
        LangfuseTraceContext secondTraceContext = LangfuseTraceContext.builder()
                .userId("otel-subscription-user-b")
                .sessionId("otel-subscription-session-b")
                .build();
        Context firstParent = LangfuseContext.storeIn(Context.root(), firstTraceContext);
        Context secondParent = LangfuseContext.storeIn(Context.root(), secondTraceContext);

        List<String> results = Flux.merge(
                        shared.contextWrite(context -> context.put(Context.class, firstParent))
                                .subscribeOn(Schedulers.parallel()),
                        shared.contextWrite(context -> context.put(Context.class, secondParent))
                                .subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertThat(results).containsExactlyInAnyOrder(
                "otel-subscription-user-a/otel-subscription-session-a",
                "otel-subscription-user-b/otel-subscription-session-b");
        assertThat(awaitSpansNamed("reactive-context-metadata", 2))
                .extracting(span -> span.getAttributes().get(
                        AttributeKey.stringKey("user.id")))
                .containsExactlyInAnyOrder(
                        "otel-subscription-user-a", "otel-subscription-user-b");
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void schedulerTransitionsKeepProviderChildrenIsolatedUnderEachObservation() {
        TestService proxy = proxy(new TestService());
        LangfuseTraceContext firstContext = LangfuseTraceContext.builder()
                .userId("scheduled-user-a")
                .build();
        LangfuseTraceContext secondContext = LangfuseTraceContext.builder()
                .userId("scheduled-user-b")
                .build();
        Context firstParent = LangfuseContext.storeIn(Context.root(), firstContext);
        Context secondParent = LangfuseContext.storeIn(Context.root(), secondContext);

        List<String> results = Flux.merge(
                        proxy.reactiveWithScheduledChild("scheduled-provider-a")
                                .contextWrite(context -> context.put(
                                        Context.class, firstParent)),
                        proxy.reactiveWithScheduledChild("scheduled-provider-b")
                                .contextWrite(context -> context.put(
                                        Context.class, secondParent)))
                .collectList()
                .block();

        assertThat(results).containsExactlyInAnyOrder(
                "scheduled-user-a:scheduled-provider-a",
                "scheduled-user-b:scheduled-provider-b");
        List<SpanData> observations = awaitSpansNamed("scheduled-reactive-parent", 2);
        SpanData firstObservation = observationForUser(observations, "scheduled-user-a");
        SpanData secondObservation = observationForUser(observations, "scheduled-user-b");
        SpanData firstChild = spanNamed("scheduled-provider-a");
        SpanData secondChild = spanNamed("scheduled-provider-b");
        assertThat(firstChild.getParentSpanId()).isEqualTo(firstObservation.getSpanId());
        assertThat(secondChild.getParentSpanId()).isEqualTo(secondObservation.getSpanId());
        assertThat(firstObservation.getSpanId()).isNotEqualTo(secondObservation.getSpanId());
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void rawThreadSignalsScopeDownstreamApplicationCallbacks() throws Exception {
        TestService target = new TestService();
        TestService proxy = proxy(target);
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("annotation-raw-user")
                .build();
        AtomicReference<LangfuseTraceContext> downstreamTraceContext =
                new AtomicReference<>();

        String result = proxy.reactiveRawThread()
                .map(value -> {
                    downstreamTraceContext.set(LangfuseContext.current());
                    Span child = otel.getOpenTelemetry().getTracer("provider-test")
                            .spanBuilder("annotation-raw-downstream-child")
                            .startSpan();
                    child.end();
                    return value;
                })
                .contextWrite(context -> context.put(
                        LangfuseContext.reactorContextKey(), traceContext))
                .block();

        assertThat(result).isEqualTo("annotation-raw-value");
        assertThat(target.rawThreadPublisher.signalReturned.await(5, TimeUnit.SECONDS)).isTrue();
        SpanData observation = spanNamed("annotation-raw-parent");
        SpanData child = spanNamed("annotation-raw-downstream-child");
        assertThat(child.getParentSpanId()).isEqualTo(observation.getSpanId());
        assertThat(downstreamTraceContext.get()).isSameAs(traceContext);
        assertThat(target.rawThreadPublisher.sourceBeforeSignal.get().isValid()).isFalse();
        assertThat(target.rawThreadPublisher.sourceAfterTerminal.get().isValid()).isFalse();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void publicRawBoundaryBridgeScopesProviderOperators() throws Exception {
        TestService target = new TestService();
        TestService proxy = proxy(target);
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("annotation-provider-user")
                .build();

        String result = proxy.reactiveRawThreadWithProviderBridge()
                .contextWrite(context -> context.put(
                        LangfuseContext.reactorContextKey(), traceContext))
                .block();

        assertThat(target.rawProviderPublisher.signalReturned.await(5, TimeUnit.SECONDS)).isTrue();
        SpanData observation = spanNamed("annotation-provider-raw-parent");
        SpanData child = spanNamed("annotation-raw-provider-child");
        assertThat(result).isEqualTo("annotation-provider-value");
        assertThat(child.getParentSpanId()).isEqualTo(observation.getSpanId());
        assertThat(target.rawProviderTraceContext.get()).isSameAs(traceContext);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void fluxRecordsTheLastElementAndEndsOnCompletion() {
        TestService proxy = proxy(new TestService());

        List<String> values = proxy.reactiveMany().collectList().block();

        assertThat(values).containsExactly("first", "last");
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getAttributes()
                .get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("last");
    }

    @Test
    void fluxDropsAnOversizedLastElementBeforeTerminalCapture() {
        LangfuseOtel langfuseOtel = LangfuseOtel.externalBuilder(otel.getOpenTelemetry())
                .contentCapturePolicy(ContentCapturePolicy.builder()
                        .captureOutput(true)
                        .maxLength(4)
                        .redactor((type, content) -> "safe")
                        .build())
                .build();
        TestService proxy = proxy(new TestService(), langfuseOtel);

        List<String> values = proxy.reactiveManyWithOversizedLastElement().collectList().block();

        assertThat(values).containsExactly("ok", "oversized");
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getAttributes()
                .get(AttributeKey.stringKey("langfuse.observation.output"))).isNull();
    }

    @Test
    void fluxUsesOneContentPolicySnapshotPerSubscription() {
        SecondLookupFailingContentPolicyLangfuseOtel langfuseOtel =
                new SecondLookupFailingContentPolicyLangfuseOtel();
        TestService proxy = proxy(new TestService(), langfuseOtel);

        List<String> values = proxy.reactiveMany().collectList().block();

        assertThat(values).containsExactly("first", "last");
        assertThat(langfuseOtel.policyLookups()).isEqualTo(1);
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getAttributes()
                .get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("last");
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void reactiveErrorAndCancellationBothEndTheirSubscriptionSpan() {
        TestService proxy = proxy(new TestService());

        StepVerifier.create(proxy.reactiveFailure())
                .expectErrorMessage("reactive boom")
                .verify();
        StepVerifier.create(proxy.reactiveNever())
                .thenCancel()
                .verify();

        assertThat(otel.getSpans()).hasSize(2);
        SpanData failed = otel.getSpans().stream()
                .filter(span -> span.getName().equals("reactive-failure"))
                .findFirst().orElseThrow();
        assertThat(failed.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("ERROR");
        SpanData cancelled = otel.getSpans().stream()
                .filter(span -> span.getName().equals("reactive-cancel"))
                .findFirst().orElseThrow();
        assertThat(cancelled.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isNull();
    }

    @Test
    void nonFatalObservationSetupFailureNeverChangesSyncOrReactiveResults() {
        TestService proxy = proxy(new TestService(), new FailingLangfuseOtel());

        assertThat(proxy.summarize("fallback")).isEqualTo("summary: fallback");
        assertThat(proxy.objectResult()).isEqualTo("object-result");
        assertThat(proxy.reactive().block()).isEqualTo("reactive-result");
        assertThat(otel.getSpans()).isEmpty();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    private TestService proxy(TestService target) {
        return proxy(target, new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    private TestService proxy(TestService target, LangfuseOtel langfuseOtel) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ObserveGenerationAspect(langfuseOtel));
        return factory.getProxy();
    }

    private List<SpanData> awaitSpansNamed(String name, int expectedCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<SpanData> matchingSpans;
        do {
            matchingSpans = otel.getSpans().stream()
                    .filter(span -> span.getName().equals(name))
                    .collect(Collectors.toList());
            if (matchingSpans.size() >= expectedCount) {
                return matchingSpans;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        } while (System.nanoTime() < deadline);
        return matchingSpans;
    }

    private SpanData observationForUser(List<SpanData> observations, String userId) {
        return observations.stream()
                .filter(span -> userId.equals(span.getAttributes().get(
                        AttributeKey.stringKey("user.id"))))
                .findFirst()
                .orElseThrow();
    }

    private SpanData spanNamed(String name) {
        return otel.getSpans().stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    static class FailingLangfuseOtel extends LangfuseOtel {
        FailingLangfuseOtel() {
            super(null, otel.getOpenTelemetry(), null, true);
        }

        @Override
        public Tracer getTracer() {
            throw new AssertionError("tracer unavailable");
        }
    }

    static class SecondLookupFailingContentPolicyLangfuseOtel extends LangfuseOtel {

        private final AtomicInteger policyLookups = new AtomicInteger();

        SecondLookupFailingContentPolicyLangfuseOtel() {
            super(null, otel.getOpenTelemetry(), null, true);
        }

        @Override
        public ContentCapturePolicy getContentCapturePolicy() {
            if (policyLookups.incrementAndGet() > 1) {
                throw new AssertionError("content policy looked up more than once");
            }
            return ContentCapturePolicy.captureAll();
        }

        int policyLookups() {
            return policyLookups.get();
        }
    }

    static class TestService {

        private final CompletableFuture<String> pending = new CompletableFuture<>();
        private final CompletableFuture<String> objectPending = new CompletableFuture<>();
        private final CustomPublisher customPublisher = new CustomPublisher("custom-result");
        private final RawThreadPublisher rawThreadPublisher =
                new RawThreadPublisher("annotation-raw-value");
        private final RawThreadPublisher rawProviderPublisher =
                new RawThreadPublisher("annotation-provider-value");
        private final AtomicReference<LangfuseTraceContext> rawProviderTraceContext =
                new AtomicReference<>();

        @ObserveGeneration(name = "summarize", model = "gpt-4o-mini", system = "openai")
        String summarize(String text) {
            return "summary: " + text;
        }

        @ObserveGeneration(name = "explode", model = "gpt-4o-mini", system = "openai")
        String fail() {
            throw new IllegalStateException("boom");
        }

        @ObserveGeneration(name = "future-generation", model = "future-model")
        CompletionStage<String> delayed() {
            return pending;
        }

        @ObserveGeneration(name = "object-generation")
        Object objectResult() {
            return "object-result";
        }

        @ObserveGeneration(name = "object-future-generation")
        Object objectDelayed() {
            return objectPending;
        }

        @ObserveGeneration(name = "reactive-generation", model = "reactive-model")
        Mono<String> reactive() {
            return Mono.defer(() -> Mono.just("reactive-result"));
        }

        @ObserveGeneration(name = "reactive-context-metadata")
        Mono<String> reactiveTraceContextMetadata() {
            return Mono.deferContextual(context -> {
                LangfuseTraceContext traceContext = context.get(
                        LangfuseContext.reactorContextKey());
                return Mono.just(traceContext.getUserId() + "/" + traceContext.getSessionId());
            });
        }

        @ObserveGeneration(name = "custom-publisher-generation")
        CustomPublisher customPublisher() {
            return customPublisher;
        }

        @ObserveGeneration(name = "reactive-parent")
        Mono<String> reactiveWithChildSpan() {
            return Mono.defer(() -> {
                Span child = otel.getOpenTelemetry().getTracer("provider-test")
                        .spanBuilder("provider-child")
                        .startSpan();
                child.end();
                return Mono.just("child-result");
            });
        }

        @ObserveGeneration(name = "scheduled-reactive-parent")
        Mono<String> reactiveWithScheduledChild(String childName) {
            return Mono.deferContextual(context -> {
                        LangfuseTraceContext traceContext = context.get(
                                LangfuseContext.reactorContextKey());
                        return Mono.delay(Duration.ofMillis(5))
                                .publishOn(Schedulers.parallel())
                                .map(ignored -> {
                                    Span child = otel.getOpenTelemetry().getTracer("provider-test")
                                            .spanBuilder(childName)
                                            .startSpan();
                                    child.end();
                                    return traceContext.getUserId() + ":" + childName;
                                });
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        }

        @ObserveGeneration(name = "annotation-raw-parent")
        Mono<String> reactiveRawThread() {
            return Mono.from(rawThreadPublisher);
        }

        @ObserveGeneration(name = "annotation-provider-raw-parent")
        Mono<String> reactiveRawThreadWithProviderBridge() {
            return Mono.from(ReactorContextPropagation.wrap(
                            rawProviderPublisher))
                    .map(value -> {
                        rawProviderTraceContext.set(LangfuseContext.current());
                        Span child = otel.getOpenTelemetry().getTracer("provider-test")
                                .spanBuilder("annotation-raw-provider-child")
                                .startSpan();
                        child.end();
                        return value;
                    });
        }

        @ObserveGeneration(name = "reactive-many")
        Flux<String> reactiveMany() {
            return Flux.just("first", "last");
        }

        @ObserveGeneration(name = "reactive-many-oversized")
        Flux<String> reactiveManyWithOversizedLastElement() {
            return Flux.just("ok", "oversized");
        }

        @ObserveGeneration(name = "reactive-failure")
        Mono<String> reactiveFailure() {
            return Mono.error(new IllegalStateException("reactive boom"));
        }

        @ObserveGeneration(name = "reactive-cancel")
        Mono<String> reactiveNever() {
            return Mono.never();
        }
    }

    static final class CustomPublisher implements Publisher<String> {

        private final Mono<String> delegate;

        private CustomPublisher(String value) {
            this.delegate = Mono.just(value);
        }

        @Override
        public void subscribe(Subscriber<? super String> subscriber) {
            delegate.subscribe(subscriber);
        }
    }

    static final class RawThreadPublisher implements Publisher<String> {

        private final String value;
        private final AtomicReference<io.opentelemetry.api.trace.SpanContext> sourceBeforeSignal =
                new AtomicReference<>();
        private final AtomicReference<io.opentelemetry.api.trace.SpanContext> sourceAfterTerminal =
                new AtomicReference<>();
        private final CountDownLatch signalReturned = new CountDownLatch(1);

        private RawThreadPublisher(String value) {
            this.value = value;
        }

        @Override
        public void subscribe(Subscriber<? super String> subscriber) {
            subscriber.onSubscribe(new Subscription() {
                private final AtomicBoolean requested = new AtomicBoolean();

                @Override
                public void request(long amount) {
                    if (!requested.compareAndSet(false, true)) {
                        return;
                    }
                    Thread rawThread = new Thread(() -> {
                        sourceBeforeSignal.set(Span.current().getSpanContext());
                        subscriber.onNext(value);
                        subscriber.onComplete();
                        sourceAfterTerminal.set(Span.current().getSpanContext());
                        signalReturned.countDown();
                    }, "annotation-raw-publisher");
                    rawThread.start();
                }

                @Override
                public void cancel() {
                }
            });
        }
    }
}
