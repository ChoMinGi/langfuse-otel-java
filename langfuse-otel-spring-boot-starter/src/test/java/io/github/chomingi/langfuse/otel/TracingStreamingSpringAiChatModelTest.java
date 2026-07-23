package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.github.chomingi.langfuse.otel.spring.ReactorContextPropagation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingStreamingSpringAiChatModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void streamCapturesRequestAndResponseAttributes() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        Prompt prompt = new Prompt("What is Langfuse?", ChatOptions.builder()
                .model("gpt-4o-mini")
                .temperature(0.3)
                .maxTokens(128)
                .topP(0.8)
                .build());

        List<ChatResponse> responses = proxy.stream(prompt).collectList().block();

        assertThat(responses).hasSize(3);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("spring-ai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("\"role\":\"user\"").contains("What is Langfuse?");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("Hello World!");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(5L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(7L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(12L);
    }

    @Test
    void streamDoesNotCreateSpanOrInvokeDelegateUntilSubscribed() {
        CountingSpanProcessor spanProcessor = new CountingSpanProcessor();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanProcessor)
                .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        PerSubscriptionStreamingChatModel target = new PerSubscriptionStreamingChatModel();
        ChatModel proxy = new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                target,
                new LangfuseOtel(null, openTelemetry, null, true));

        try {
            Flux<ChatResponse> stream = proxy.stream(new Prompt("not-subscribed"));

            assertThat(stream).isNotNull();
            assertThat(target.streamInvocations()).isZero();
            assertThat(spanProcessor.startedSpans()).isZero();
            assertThat(spanProcessor.endedSpans()).isZero();
        } finally {
            tracerProvider.shutdown();
        }
    }

    @Test
    void streamUsesMetadataOnlyContentDefaultFromPublicBuilder() {
        LangfuseOtel langfuse = LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build();
        ChatModel proxy = new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                new StubStreamingSpringAiChatModel(), langfuse);

        proxy.stream(new Prompt("sensitive stream prompt")).collectList().block();

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")))
                .isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isNull();
    }

    @Test
    void nonFatalContentPolicySetupFailureFallsBackWithoutStartingASpan() {
        FailingContentPolicyLangfuseOtel langfuse =
                new FailingContentPolicyLangfuseOtel();
        ChatModel proxy = new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                new StubStreamingSpringAiChatModel(), langfuse);

        List<ChatResponse> responses = proxy.stream(new Prompt("policy failure"))
                .collectList()
                .block();

        assertThat(responses).hasSize(3);
        assertThat(langfuse.policyLookups()).isEqualTo(1);
        assertThat(otel.getSpans()).isEmpty();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void nonFatalInvocationContextFailureEndsTheStartedSpanAndFallsBack() {
        AssemblyContextStreamingChatModel target = new AssemblyContextStreamingChatModel();
        ChatModel proxy = proxy(target);
        FailingRecordingSpanContext failingContext = new FailingRecordingSpanContext();
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("failing-context-user")
                .build();

        List<ChatResponse> responses = proxy.stream(new Prompt("context failure"))
                .contextWrite(context -> context
                        .put(Context.class, failingContext)
                        .put(LangfuseContext.reactorContextKey(), traceContext))
                .collectList()
                .block();

        assertThat(responses).hasSize(1);
        assertThat(failingContext.rejectedWrites()).isEqualTo(1);
        assertThat(target.assemblySpanId.get()).isEqualTo(
                Span.getInvalid().getSpanContext().getSpanId());
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void reactorTraceContextIsStoredInTheSpanParentContext() {
        CountingSpanProcessor spanProcessor = new CountingSpanProcessor();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanProcessor)
                .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        ChatModel proxy = new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                new PerSubscriptionStreamingChatModel(),
                new LangfuseOtel(null, openTelemetry, null, true));
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("reactive-user")
                .sessionId("reactive-session")
                .build();

        try {
            proxy.stream(new Prompt("context"))
                    .contextWrite(context -> context.put(
                            LangfuseContext.reactorContextKey(), traceContext))
                    .collectList()
                    .block();

            assertThat(spanProcessor.startedSpans()).isEqualTo(1);
            assertThat(spanProcessor.endedSpans()).isEqualTo(1);
            assertThat(spanProcessor.lastTraceContext()).isSameAs(traceContext);
        } finally {
            tracerProvider.shutdown();
        }
    }

    @Test
    void reactorTraceContextIsAppliedWhenExternalSdkHasNoLangfuseProcessor() {
        ChatModel proxy = proxy(new PerSubscriptionStreamingChatModel());
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("external-reactive-user")
                .sessionId("external-reactive-session")
                .tags("production")
                .build();

        proxy.stream(new Prompt("context attributes"))
                .contextWrite(context -> context.put(
                        LangfuseContext.reactorContextKey(), traceContext))
                .collectList()
                .block();

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo("external-reactive-user");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("session.id")))
                .isEqualTo("external-reactive-session");
        assertThat(span.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags")))
                .containsExactly("production");
    }

    @Test
    void reactorOtelContextProvidesTheWrapperParentAndLangfuseMetadataFallback() {
        ChatModel proxy = proxy(new PerSubscriptionStreamingChatModel());
        Span parent = otel.getOpenTelemetry().getTracer("parent-test")
                .spanBuilder("reactor-parent")
                .startSpan();
        String parentSpanId = parent.getSpanContext().getSpanId();
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("otel-context-user")
                .sessionId("otel-context-session")
                .build();
        Context reactorParent = LangfuseContext.storeIn(Context.root().with(parent), traceContext);

        try {
            proxy.stream(new Prompt("otel parent"))
                    .contextWrite(context -> context.put(Context.class, reactorParent))
                    .collectList()
                    .block();
        } finally {
            parent.end();
        }

        SpanData wrapper = otel.getSpans().stream()
                .filter(span -> span.getName().equals("persubscriptionstreaming.chat"))
                .findFirst().orElseThrow();
        assertThat(wrapper.getParentSpanId()).isEqualTo(parentSpanId);
        assertThat(wrapper.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo("otel-context-user");
        assertThat(wrapper.getAttributes().get(AttributeKey.stringKey("session.id")))
                .isEqualTo("otel-context-session");
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void repeatedConcurrentSubscriptionsHaveIndependentSpansAndAccumulators() {
        PerSubscriptionStreamingChatModel target = new PerSubscriptionStreamingChatModel();
        ChatModel proxy = proxy(target);
        Flux<ChatResponse> stream = proxy.stream(new Prompt("repeat"));

        List<ChatResponse> responses = Flux.merge(
                        stream.subscribeOn(Schedulers.parallel()),
                        stream.subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        assertThat(responses).hasSize(2);
        assertThat(target.streamInvocations()).isEqualTo(2);
        awaitSpanCount("persubscriptionstreaming.chat", 2);
        assertThat(otel.getSpans()).hasSize(2);
        assertThat(otel.getSpans())
                .extracting(span -> span.getAttributes().get(
                        AttributeKey.stringKey("langfuse.observation.output")))
                .containsExactlyInAnyOrder("subscription-1", "subscription-2");
    }

    @Test
    void sourceSubscriptionWorkUsesTheWrapperObservationAsItsParent() {
        ChatModel proxy = proxy(new SourceSubscriptionSpanStreamingChatModel());

        proxy.stream(new Prompt("source subscription child")).collectList().block();

        SpanData wrapper = otel.getSpans().stream()
                .filter(span -> span.getName().equals("sourcesubscriptionspanstreaming.chat"))
                .findFirst().orElseThrow();
        SpanData provider = otel.getSpans().stream()
                .filter(span -> span.getName().equals("source-subscription-provider"))
                .findFirst().orElseThrow();
        assertThat(provider.getParentSpanId()).isEqualTo(wrapper.getSpanId());
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void schedulerTransitionsKeepConcurrentProviderChildrenUnderTheirWrappers() {
        ChatModel firstProxy = proxy(new SchedulerSwitchingProviderSpanStreamingChatModel(
                "spring-scheduled-provider-a"));
        ChatModel secondProxy = proxy(new SchedulerSwitchingProviderSpanStreamingChatModel(
                "spring-scheduled-provider-b"));
        LangfuseTraceContext firstContext = LangfuseTraceContext.builder()
                .userId("spring-scheduled-user-a")
                .build();
        LangfuseTraceContext secondContext = LangfuseTraceContext.builder()
                .userId("spring-scheduled-user-b")
                .build();

        List<ChatResponse> responses = Flux.merge(
                        firstProxy.stream(new Prompt("scheduler-a"))
                                .contextWrite(context -> context.put(
                                        LangfuseContext.reactorContextKey(), firstContext)),
                        secondProxy.stream(new Prompt("scheduler-b"))
                                .contextWrite(context -> context.put(
                                        LangfuseContext.reactorContextKey(), secondContext)))
                .collectList()
                .block();

        assertThat(responses).hasSize(2);
        awaitSpanCount("schedulerswitchingproviderspanstreaming.chat", 2);
        SpanData firstWrapper = schedulerWrapperForUser("spring-scheduled-user-a");
        SpanData secondWrapper = schedulerWrapperForUser("spring-scheduled-user-b");
        SpanData firstProvider = spanNamed("spring-scheduled-provider-a");
        SpanData secondProvider = spanNamed("spring-scheduled-provider-b");
        assertThat(firstProvider.getParentSpanId()).isEqualTo(firstWrapper.getSpanId());
        assertThat(secondProvider.getParentSpanId()).isEqualTo(secondWrapper.getSpanId());
        assertThat(firstWrapper.getSpanId()).isNotEqualTo(secondWrapper.getSpanId());
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void rawPublisherBoundaryBridgeScopesProviderOperatorsAndMetadata() throws Exception {
        RawThreadBoundaryStreamingChatModel target =
                new RawThreadBoundaryStreamingChatModel();
        ChatModel proxy = proxy(target);
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("spring-raw-user")
                .sessionId("spring-raw-session")
                .build();

        List<ChatResponse> responses = proxy.stream(new Prompt("raw publisher"))
                .contextWrite(context -> context.put(
                        LangfuseContext.reactorContextKey(), traceContext))
                .collectList()
                .block();

        assertThat(responses).hasSize(1);
        assertThat(target.signalReturned.await(5, TimeUnit.SECONDS)).isTrue();
        SpanData wrapper = otel.getSpans().stream()
                .filter(span -> span.getName().equals("rawthreadboundarystreaming.chat"))
                .findFirst().orElseThrow();
        SpanData provider = spanNamed("spring-raw-provider-child");
        assertThat(provider.getParentSpanId()).isEqualTo(wrapper.getSpanId());
        assertThat(target.providerTraceContext.get()).isSameAs(traceContext);
        assertThat(target.sourceBeforeSignal.get().isValid()).isFalse();
        assertThat(target.sourceAfterTerminal.get().isValid()).isFalse();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void delegateStreamAssemblyUsesTheFullSubscriptionContext() {
        AssemblyContextStreamingChatModel target = new AssemblyContextStreamingChatModel();
        ChatModel proxy = proxy(target);
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("spring-assembly-user")
                .build();

        proxy.stream(new Prompt("assembly context"))
                .contextWrite(context -> context.put(
                        LangfuseContext.reactorContextKey(), traceContext))
                .collectList()
                .block();

        SpanData wrapper = otel.getSpans().stream()
                .filter(span -> span.getName().equals("assemblycontextstreaming.chat"))
                .findFirst().orElseThrow();
        assertThat(target.assemblySpanId.get()).isEqualTo(wrapper.getSpanId());
        assertThat(target.assemblyTraceContext.get()).isSameAs(traceContext);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void streamRecordsExceptionOnError() {
        ChatModel proxy = proxy(new ErrorStreamingSpringAiChatModel());

        Prompt prompt = new Prompt("fail");

        StepVerifier.create(proxy.stream(prompt))
                .expectError(RuntimeException.class)
                .verify();

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
    }

    @Test
    void streamDropsOutputWhenRawContentExceedsTheCaptureLimit() {
        LangfuseOtel langfuse = LangfuseOtel.externalBuilder(otel.getOpenTelemetry())
                .contentCapturePolicy(ContentCapturePolicy.builder()
                        .captureOutput(true)
                        .maxLength(5)
                        .redactor((type, content) -> content.replace("Hello World!", "safe"))
                        .build())
                .build();
        ChatModel proxy = new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                new StubStreamingSpringAiChatModel(), langfuse);

        List<ChatResponse> responses = proxy.stream(new Prompt("bounded output"))
                .collectList()
                .block();

        assertThat(responses).hasSize(3);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isNull();
    }

    @Test
    void synchronousFatalDelegateFailureStillEndsTheSpan() {
        ChatModel proxy = proxy(new FatalStreamingSpringAiChatModel());

        assertThatThrownBy(() -> proxy.stream(new Prompt("fatal")).collectList().block())
                .isInstanceOf(LinkageError.class)
                .hasMessage("fatal stream error");

        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    @Test
    void nullDelegateFluxBecomesAnErrorAndStillEndsTheSpan() {
        ChatModel proxy = proxy(new NullFluxStreamingSpringAiChatModel());

        StepVerifier.create(proxy.stream(new Prompt("null flux")))
                .expectErrorMatches(error -> error instanceof NullPointerException
                        && error.getMessage().contains("must not return null"))
                .verify();

        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    @Test
    void syncCallStillWorks() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        ChatResponse response = proxy.call(new Prompt("hello", ChatOptions.builder()
                .model("gpt-4o-mini").build()));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("sync answer: hello");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("spring-ai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("sync answer: hello");
    }

    @Test
    void completionStartTimeIsRecorded() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        proxy.stream(new Prompt("hi")).collectList().block();

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        String startTime = span.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.completion_start_time"));
        assertThat(startTime).isNotNull();
        assertThat(java.time.Instant.parse(startTime)).isBefore(java.time.Instant.now());
    }

    @Test
    void streamCancellationEndsSpan() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());

        StepVerifier.create(proxy.stream(new Prompt("hello")))
                .expectNextCount(1)
                .thenCancel()
                .verify();

        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void streamSpanRemainsRecordingThroughDownstreamTerminalCallbacks() {
        ChatModel proxy = proxy(new StubStreamingSpringAiChatModel());
        AtomicBoolean recordingInDownstreamComplete = new AtomicBoolean(false);

        proxy.stream(new Prompt("terminal callback"))
                .doOnComplete(() -> recordingInDownstreamComplete.set(
                        Span.current().isRecording()))
                .collectList()
                .block();

        assertThat(recordingInDownstreamComplete).isTrue();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    private ChatModel proxy(ChatModel target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingSpringAiChatModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    private SpanData schedulerWrapperForUser(String userId) {
        return otel.getSpans().stream()
                .filter(span -> span.getName().equals(
                        "schedulerswitchingproviderspanstreaming.chat"))
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

    private void awaitSpanCount(String name, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long count = otel.getSpans().stream()
                    .filter(span -> span.getName().equals(name))
                    .count();
            if (count >= expected) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(otel.getSpans()).filteredOn(span -> span.getName().equals(name))
                .hasSize(expected);
    }

    static class StubStreamingSpringAiChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            String input = prompt.getInstructions().isEmpty() ? "" : prompt.getInstructions().get(0).getText();
            Usage usage = new StubUsage(5, 7);
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model("gpt-4o-mini")
                    .usage(usage)
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("sync answer: " + input))), metadata);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            ChatResponse chunk1 = new ChatResponse(
                    List.of(new Generation(new AssistantMessage("Hello"))));
            ChatResponse chunk2 = new ChatResponse(
                    List.of(new Generation(new AssistantMessage(" World"))));

            Usage finalUsage = new StubUsage(5, 7);
            ChatResponseMetadata finalMeta = ChatResponseMetadata.builder()
                    .model("gpt-4o-mini")
                    .usage(finalUsage)
                    .build();
            ChatResponse chunk3 = new ChatResponse(
                    List.of(new Generation(new AssistantMessage("!"))), finalMeta);

            return Flux.just(chunk1, chunk2, chunk3);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder()
                    .model("gpt-4o-mini")
                    .temperature(0.4)
                    .maxTokens(256)
                    .topP(0.9)
                    .build();
        }
    }

    static class FailingContentPolicyLangfuseOtel extends LangfuseOtel {

        private final AtomicInteger policyLookups = new AtomicInteger();

        FailingContentPolicyLangfuseOtel() {
            super(null, otel.getOpenTelemetry(), null, true);
        }

        @Override
        public ContentCapturePolicy getContentCapturePolicy() {
            policyLookups.incrementAndGet();
            throw new AssertionError("content policy unavailable");
        }

        int policyLookups() {
            return policyLookups.get();
        }
    }

    static class FailingRecordingSpanContext implements Context {

        private final Context delegate;
        private final AtomicInteger rejectedWrites;

        FailingRecordingSpanContext() {
            this(Context.root(), new AtomicInteger());
        }

        private FailingRecordingSpanContext(Context delegate, AtomicInteger rejectedWrites) {
            this.delegate = delegate;
            this.rejectedWrites = rejectedWrites;
        }

        @Override
        public <V> V get(ContextKey<V> key) {
            return delegate.get(key);
        }

        @Override
        public <V> Context with(ContextKey<V> key, V value) {
            if (value instanceof Span && ((Span) value).isRecording()) {
                rejectedWrites.incrementAndGet();
                throw new AssertionError("span context enrichment unavailable");
            }
            return new FailingRecordingSpanContext(
                    delegate.with(key, value), rejectedWrites);
        }

        int rejectedWrites() {
            return rejectedWrites.get();
        }
    }

    static class SourceSubscriptionSpanStreamingChatModel extends StubStreamingSpringAiChatModel {
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                Span provider = otel.getOpenTelemetry().getTracer("provider-test")
                        .spanBuilder("source-subscription-provider")
                        .startSpan();
                provider.end();
                return Flux.just(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("deferred response")))));
            });
        }
    }

    static class SchedulerSwitchingProviderSpanStreamingChatModel
            extends StubStreamingSpringAiChatModel {

        private final String childSpanName;

        SchedulerSwitchingProviderSpanStreamingChatModel(String childSpanName) {
            this.childSpanName = childSpanName;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Mono.delay(Duration.ofMillis(5))
                    .publishOn(Schedulers.parallel())
                    .map(ignored -> {
                        Span provider = otel.getOpenTelemetry().getTracer("provider-test")
                                .spanBuilder(childSpanName)
                                .startSpan();
                        provider.end();
                        return new ChatResponse(List.of(
                                new Generation(new AssistantMessage(childSpanName))));
                    })
                    .flux()
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }

    static class RawThreadBoundaryStreamingChatModel extends StubStreamingSpringAiChatModel {

        private final AtomicReference<io.opentelemetry.api.trace.SpanContext> sourceBeforeSignal =
                new AtomicReference<>();
        private final AtomicReference<io.opentelemetry.api.trace.SpanContext> sourceAfterTerminal =
                new AtomicReference<>();
        private final AtomicReference<LangfuseTraceContext> providerTraceContext =
                new AtomicReference<>();
        private final CountDownLatch signalReturned = new CountDownLatch(1);

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            Publisher<ChatResponse> rawPublisher = subscriber ->
                    subscriber.onSubscribe(new Subscription() {
                        private final AtomicBoolean requested = new AtomicBoolean();

                        @Override
                        public void request(long amount) {
                            if (!requested.compareAndSet(false, true)) {
                                return;
                            }
                            Thread rawThread = new Thread(() -> {
                                sourceBeforeSignal.set(Span.current().getSpanContext());
                                subscriber.onNext(new ChatResponse(List.of(
                                        new Generation(new AssistantMessage("raw response")))));
                                subscriber.onComplete();
                                sourceAfterTerminal.set(Span.current().getSpanContext());
                                signalReturned.countDown();
                            }, "spring-ai-raw-publisher");
                            rawThread.start();
                        }

                        @Override
                        public void cancel() {
                        }
                    });

            return Flux.from(ReactorContextPropagation.wrap(rawPublisher))
                    .map(response -> {
                        providerTraceContext.set(LangfuseContext.current());
                        Span provider = otel.getOpenTelemetry().getTracer("provider-test")
                                .spanBuilder("spring-raw-provider-child")
                                .startSpan();
                        provider.end();
                        return response;
                    });
        }
    }

    static class AssemblyContextStreamingChatModel extends StubStreamingSpringAiChatModel {

        private final AtomicReference<String> assemblySpanId = new AtomicReference<>();
        private final AtomicReference<LangfuseTraceContext> assemblyTraceContext =
                new AtomicReference<>();

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            assemblySpanId.set(Span.current().getSpanContext().getSpanId());
            assemblyTraceContext.set(LangfuseContext.current());
            return Flux.just(new ChatResponse(List.of(
                    new Generation(new AssistantMessage("assembly response")))));
        }
    }

    @Test
    void streamWithNullMetadataInChunks() {
        ChatModel proxy = proxy(new NullMetadataStreamingChatModel());

        List<ChatResponse> chunks = proxy.stream(new Prompt("test")).collectList().block();

        assertThat(chunks).hasSize(2);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("AB");
    }

    @Test
    void streamWithNullOutputInChunks() {
        ChatModel proxy = proxy(new NullOutputStreamingChatModel());

        List<ChatResponse> chunks = proxy.stream(new Prompt("test")).collectList().block();

        assertThat(chunks).hasSize(3);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("X");
    }

    static class NullMetadataStreamingChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("A")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("B")))));
        }

        @Override
        public ChatOptions getDefaultOptions() { return ChatOptions.builder().model("m").build(); }
    }

    static class NullOutputStreamingChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage((String) null)))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("X")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage((String) null)))));
        }

        @Override
        public ChatOptions getDefaultOptions() { return ChatOptions.builder().model("m").build(); }
    }

    static class ErrorStreamingSpringAiChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new RuntimeException("sync error");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(new RuntimeException("stream error"));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("gpt-4o-mini").build();
        }
    }

    static class FatalStreamingSpringAiChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new LinkageError("fatal stream error");
        }
    }

    static class NullFluxStreamingSpringAiChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return null;
        }
    }

    static class PerSubscriptionStreamingChatModel implements ChatModel {

        private final AtomicInteger streamInvocations = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int invocation = streamInvocations.incrementAndGet();
            return Flux.just(new ChatResponse(List.of(
                    new Generation(new AssistantMessage("subscription-" + invocation)))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("gpt-4o-mini").build();
        }

        int streamInvocations() {
            return streamInvocations.get();
        }
    }

    static class CountingSpanProcessor implements SpanProcessor {

        private final AtomicInteger startedSpans = new AtomicInteger();
        private final AtomicInteger endedSpans = new AtomicInteger();
        private final AtomicReference<LangfuseTraceContext> lastTraceContext = new AtomicReference<>();

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            startedSpans.incrementAndGet();
            lastTraceContext.set(LangfuseContext.from(parentContext));
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            endedSpans.incrementAndGet();
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        int startedSpans() {
            return startedSpans.get();
        }

        int endedSpans() {
            return endedSpans.get();
        }

        LangfuseTraceContext lastTraceContext() {
            return lastTraceContext.get();
        }
    }

    static class StubUsage implements Usage {
        private final Integer promptTokens;
        private final Integer completionTokens;

        StubUsage(Integer promptTokens, Integer completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public Integer getPromptTokens() { return promptTokens; }

        @Override
        public Integer getCompletionTokens() { return completionTokens; }

        @Override
        public Object getNativeUsage() { return null; }
    }
}
