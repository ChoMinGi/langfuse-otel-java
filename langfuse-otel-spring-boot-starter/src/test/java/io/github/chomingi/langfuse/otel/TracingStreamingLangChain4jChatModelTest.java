package io.github.chomingi.langfuse.otel;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.spring.LangChain4jStreamingContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TracingStreamingLangChain4jChatModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void streamingChatCapturesRequestAndResponseAttributes() throws Exception {
        StreamingChatModel proxy = streamingProxy(new StubStreamingLangChain4jChatModel());

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder accumulated = new StringBuilder();

        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from("be concise"), UserMessage.from("hello"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gpt-4o-mini")
                        .temperature(0.2)
                        .topP(0.7)
                        .maxOutputTokens(64)
                        .build())
                .build();

        proxy.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                accumulated.append(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        ChatResponse response = future.get(5, TimeUnit.SECONDS);

        assertThat(accumulated.toString()).isEqualTo("HelloWorld");
        assertThat(response.aiMessage().text()).isEqualTo("HelloWorld");
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("\"role\":\"user\"").contains("hello");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("HelloWorld");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(4L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(5L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(9L);
    }

    @Test
    void publicBuilderDefaultsAutomaticStreamingChatInstrumentationToMetadataOnly() throws Exception {
        StreamingChatModel proxy = streamingProxy(
                new StubStreamingLangChain4jChatModel(),
                LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("confidential streaming input"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gpt-4o-mini")
                        .build())
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        ChatResponse response = future.get(5, TimeUnit.SECONDS);

        assertThat(response.aiMessage().text()).isEqualTo("HelloWorld");
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")))
                .isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(4L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")))
                .isEqualTo(5L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens")))
                .isEqualTo(9L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isNull();
    }

    @Test
    void publicBuilderMetadataOnlyRetainsStreamingErrorMetadata() {
        StreamingChatModel proxy = streamingProxy(
                new ErrorStreamingLangChain4jChatModel(),
                LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        CompletableFuture<Void> future = new CompletableFuture<>();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("confidential failure input"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(null);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        assertThat(future).isCompletedExceptionally();
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message")))
                .isEqualTo(RuntimeException.class.getName());
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isNull();
    }

    @Test
    void streamingCompleteResponseIsBoundedByTheCapturePolicy() throws Exception {
        LangfuseOtel langfuse = LangfuseOtel.externalBuilder(otel.getOpenTelemetry())
                .contentCapturePolicy(ContentCapturePolicy.builder()
                        .captureOutput(true)
                        .maxLength(5)
                        .build())
                .build();
        StreamingChatModel proxy = streamingProxy(new StubStreamingLangChain4jChatModel(), langfuse);
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("bounded output"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        assertThat(future.get(5, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("HelloWorld");
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("Hello");
    }

    @Test
    void setupFailureAfterSpanCreationEndsSpanBeforeFallingBack() {
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new SetupFailureStreamingLangChain4jChatModel(),
                        new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
        AtomicReference<ChatResponse> completedResponse = new AtomicReference<>();

        proxy.doChat(ChatRequest.builder()
                .messages(UserMessage.from("fallback"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completedResponse.set(response);
            }

            @Override
            public void onError(Throwable error) {}
        });

        assertThat(completedResponse.get().aiMessage().text()).isEqualTo("fallback response");
        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void activeStateStorageFailureIsNonFatalAndTheSpanStillEnds() {
        FailingContext failingContext = new FailingContext(3);
        AtomicReference<ChatResponse> completedResponse = new AtomicReference<>();
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new StubStreamingLangChain4jChatModel(),
                        new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));

        try (Scope ignored = failingContext.makeCurrent()) {
            proxy.doChat(ChatRequest.builder().messages(UserMessage.from("state failure")).build(),
                    responseHandler(completedResponse));
        }

        assertThat(completedResponse.get().aiMessage().text()).isEqualTo("HelloWorld");
        assertThat(failingContext.writes).hasValue(3);
        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void fallbackStateStorageFailureIsNonFatalAndDoesNotRetryUnsafely() {
        FailingContext failingContext = new FailingContext(2);
        AtomicReference<ChatResponse> completedResponse = new AtomicReference<>();
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new SetupFailureStreamingLangChain4jChatModel(),
                        new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));

        try (Scope ignored = failingContext.makeCurrent()) {
            proxy.doChat(ChatRequest.builder().messages(UserMessage.from("fallback state failure")).build(),
                    responseHandler(completedResponse));
        }

        assertThat(completedResponse.get().aiMessage().text()).isEqualTo("fallback response");
        assertThat(failingContext.writes).hasValue(2);
        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void callbackInstrumentationPreparationFailureEndsTheSpanAndUsesTheOriginalHandler() {
        AtomicReference<ChatResponse> completedResponse = new AtomicReference<>();
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new StubStreamingLangChain4jChatModel(),
                        new FailingSecondCapturePolicyLangfuseOtel(otel.getOpenTelemetry()));

        proxy.doChat(ChatRequest.builder().messages(UserMessage.from("callback setup failure")).build(),
                responseHandler(completedResponse));

        assertThat(completedResponse.get().aiMessage().text()).isEqualTo("HelloWorld");
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void streamingChatRecordsErrorOnHandlerError() throws Exception {
        StreamingChatModel proxy = streamingProxy(new ErrorStreamingLangChain4jChatModel());

        CompletableFuture<Void> future = new CompletableFuture<>();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("fail"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(null);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        assertThat(future).isCompletedExceptionally();
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
    }

    @Test
    void completionStartTimeIsRecorded() throws Exception {
        StreamingChatModel proxy = streamingProxy(new StubStreamingLangChain4jChatModel());

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        future.get(5, TimeUnit.SECONDS);
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        String startTime = span.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.completion_start_time"));
        assertThat(startTime).isNotNull();
        assertThat(java.time.Instant.parse(startTime)).isBefore(java.time.Instant.now());
    }

    @Test
    void asynchronousCallbacksKeepTheSpanOpenUntilOneTerminalSignalAndRestoreWorkerScope() throws Exception {
        DeferredCallbackLangChain4jChatModel target = new DeferredCallbackLangChain4jChatModel();
        StreamingChatModel proxy = streamingProxy(target);
        AtomicReference<Boolean> workerScopeRestored = new AtomicReference<>();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        proxy.chat(ChatRequest.builder()
                .messages(UserMessage.from("async"))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {
                failed.incrementAndGet();
            }
        });

        assertThat(otel.getSpans()).isEmpty();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();

        CompletableFuture.runAsync(() -> {
            target.handler.get().onPartialResponse("async-token");
            target.handler.get().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("async-token"))
                    .build());
            target.handler.get().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("duplicate-terminal"))
                    .build());
            target.handler.get().onError(new IllegalStateException("late terminal"));
            workerScopeRestored.set(!Span.current().getSpanContext().isValid());
        }).get(5, TimeUnit.SECONDS);

        assertThat(workerScopeRestored).hasValue(true);
        assertThat(completed).hasValue(1);
        assertThat(failed).hasValue(0);
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getAttributes()
                .get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("async-token");
    }

    @Test
    void concurrentPartialAndTerminalCallbacksAreSerializedAndLatePartialsAreSuppressed() throws Exception {
        DeferredCallbackLangChain4jChatModel target = new DeferredCallbackLangChain4jChatModel();
        StreamingChatModel proxy = streamingProxy(target);
        CountDownLatch partialEntered = new CountDownLatch(1);
        CountDownLatch releasePartial = new CountDownLatch(1);
        CountDownLatch terminalAttempted = new CountDownLatch(1);
        CountDownLatch terminalReturned = new CountDownLatch(1);
        AtomicInteger partials = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<Boolean> partialSpanWasCurrent = new AtomicReference<>();
        ExecutorService callbacks = Executors.newFixedThreadPool(2);

        proxy.doChat(ChatRequest.builder().messages(UserMessage.from("callback race")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partial) {
                        partials.incrementAndGet();
                        partialSpanWasCurrent.set(Span.current().getSpanContext().isValid());
                        partialEntered.countDown();
                        try {
                            if (!releasePartial.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("partial callback was not released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        completions.incrementAndGet();
                    }

                    @Override
                    public void onError(Throwable error) {
                        throw new AssertionError(error);
                    }
                });

        try {
            Future<?> partial = callbacks.submit(() ->
                    target.handler.get().onPartialResponse("first"));
            assertThat(partialEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> terminal = callbacks.submit(() -> {
                terminalAttempted.countDown();
                target.handler.get().onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("done"))
                        .build());
                terminalReturned.countDown();
            });
            assertThat(terminalAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(terminalReturned.getCount()).isEqualTo(1L);
            assertThat(completions).hasValue(0);

            releasePartial.countDown();
            partial.get(5, TimeUnit.SECONDS);
            terminal.get(5, TimeUnit.SECONDS);

            target.handler.get().onPartialResponse("late");
            assertThat(partials).hasValue(1);
            assertThat(completions).hasValue(1);
            assertThat(partialSpanWasCurrent).hasValue(true);
            assertThat(otel.getSpans()).hasSize(1);
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        } finally {
            releasePartial.countDown();
            callbacks.shutdownNow();
        }
    }

    @Test
    void sameRequestConcurrentProviderWorkersKeepParentAndMetadataIsolated() throws Exception {
        ExecutorService providerExecutor = Executors.newFixedThreadPool(2);
        ContextAwareAsyncProvider target = new ContextAwareAsyncProvider(
                providerExecutor, otel.getOpenTelemetry().getTracer("provider-test"), 2);
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        target, new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
        ChatRequest sharedRequest = ChatRequest.builder()
                .messages(UserMessage.from("same-request"))
                .build();
        Map<String, AtomicReference<String>> callbackSpanIds = new ConcurrentHashMap<>();
        Map<String, AtomicReference<String>> callbackUsers = new ConcurrentHashMap<>();
        CompletableFuture<ChatResponse> first = new CompletableFuture<>();
        CompletableFuture<ChatResponse> second = new CompletableFuture<>();

        try {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(() -> invokeWithMetadata(
                            proxy, sharedRequest, "user-a", "session-a", first,
                            callbackSpanIds, callbackUsers)),
                    CompletableFuture.runAsync(() -> invokeWithMetadata(
                            proxy, sharedRequest, "user-b", "session-b", second,
                            callbackSpanIds, callbackUsers)))
                    .get(5, TimeUnit.SECONDS);

            assertThat(first.get(5, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("user-a");
            assertThat(second.get(5, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("user-b");
            assertThat(target.tasksCompleted.await(5, TimeUnit.SECONDS)).isTrue();

            List<SpanData> spans = otel.getSpans();
            assertThat(spans).hasSize(4);
            for (String user : List.of("user-a", "user-b")) {
                SpanData generation = spans.stream()
                        .filter(span -> user.equals(span.getAttributes()
                                .get(AttributeKey.stringKey("user.id"))))
                        .findFirst()
                        .orElseThrow();
                SpanData providerChild = spans.stream()
                        .filter(span -> span.getName().equals("provider-child-" + user))
                        .findFirst()
                        .orElseThrow();

                assertThat(providerChild.getTraceId()).isEqualTo(generation.getTraceId());
                assertThat(providerChild.getParentSpanId()).isEqualTo(generation.getSpanId());
                assertThat(callbackSpanIds.get(user)).hasValue(generation.getSpanId());
                assertThat(callbackUsers.get(user)).hasValue(user);
                assertThat(target.workerUsers.get(user)).isEqualTo(user);
                assertThat(target.workerSessions.get(user))
                        .isEqualTo(user.equals("user-a") ? "session-a" : "session-b");
            }

            assertThat(providerExecutor.submit(() ->
                    !Span.current().getSpanContext().isValid()
                            && LangfuseContext.getUserId() == null).get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            providerExecutor.shutdownNow();
        }
    }

    @Test
    void retainedSnapshotPropagatesLateScheduledWorkAndExpiresAtTerminal() throws Exception {
        ScheduledExecutorService rawScheduler = Executors.newSingleThreadScheduledExecutor();
        LateSchedulingProvider target = new LateSchedulingProvider();
        StreamingChatModel proxy = streamingProxy(target);
        CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
        LangfuseTraceContext metadata = LangfuseTraceContext.builder()
                .userId("late-user")
                .sessionId("late-session")
                .build();

        try {
            try (Scope ignored = LangfuseContext.makeCurrent(metadata)) {
                proxy.doChat(ChatRequest.builder()
                                .messages(UserMessage.from("late scheduling"))
                                .build(),
                        new StreamingChatResponseHandler() {
                            @Override
                            public void onPartialResponse(String partial) {
                            }

                            @Override
                            public void onCompleteResponse(ChatResponse response) {
                                completed.complete(response);
                            }

                            @Override
                            public void onError(Throwable error) {
                                completed.completeExceptionally(error);
                            }
                        });
            }

            assertThat(Span.current().getSpanContext().isValid()).isFalse();
            LangChain4jStreamingContext.Snapshot snapshot = target.snapshot.get();
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.isInvocationBound()).isTrue();
            assertThat(snapshot.isActive()).isTrue();
            assertThat(snapshot.traceContext().getUserId()).isEqualTo("late-user");

            ScheduledExecutorService scheduled = snapshot.taskWrapping(rawScheduler);
            ExecutorService executor = snapshot.taskWrapping((ExecutorService) rawScheduler);
            Executor plainExecutor = snapshot.taskWrapping((Executor) rawScheduler);
            assertThat(scheduled).isInstanceOf(ScheduledExecutorService.class);
            assertThat(executor).isInstanceOf(ExecutorService.class);

            CompletableFuture<String> plainExecutorUser = new CompletableFuture<>();
            plainExecutor.execute(() -> plainExecutorUser.complete(LangfuseContext.getUserId()));
            Future<String> executorServiceUser = executor.submit(LangfuseContext::getUserId);
            assertThat(plainExecutorUser.get(5, TimeUnit.SECONDS)).isEqualTo("late-user");
            assertThat(executorServiceUser.get(5, TimeUnit.SECONDS)).isEqualTo("late-user");

            Tracer tracer = otel.getOpenTelemetry().getTracer("late-provider-test");
            AtomicReference<String> workerUser = new AtomicReference<>();
            AtomicReference<String> workerSession = new AtomicReference<>();
            ScheduledFuture<?> providerWork = scheduled.schedule(() -> {
                workerUser.set(LangfuseContext.getUserId());
                workerSession.set(LangfuseContext.getSessionId());
                Span child = tracer.spanBuilder("late-provider-child").startSpan();
                try (Scope ignored = child.makeCurrent()) {
                    target.handler.get().onPartialResponse("late");
                    target.handler.get().onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("late"))
                            .build());
                } finally {
                    child.end();
                }
            }, 1, TimeUnit.MILLISECONDS);

            providerWork.get(5, TimeUnit.SECONDS);
            assertThat(completed.get(5, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("late");
            assertThat(workerUser).hasValue("late-user");
            assertThat(workerSession).hasValue("late-session");
            assertThat(snapshot.isActive()).isFalse();

            AtomicBoolean expiredTaskRan = new AtomicBoolean();
            AtomicReference<Boolean> expiredTaskHadSpan = new AtomicReference<>();
            AtomicReference<String> expiredTaskUser = new AtomicReference<>();
            scheduled.submit(() -> {
                expiredTaskRan.set(true);
                expiredTaskHadSpan.set(Span.current().getSpanContext().isValid());
                expiredTaskUser.set(LangfuseContext.getUserId());
            }).get(5, TimeUnit.SECONDS);

            assertThat(expiredTaskRan).isTrue();
            assertThat(expiredTaskHadSpan).hasValue(false);
            assertThat(expiredTaskUser).hasValue(null);
            assertThat(rawScheduler.submit(() ->
                    !Span.current().getSpanContext().isValid()
                            && LangfuseContext.getUserId() == null)
                    .get(5, TimeUnit.SECONDS)).isTrue();

            List<SpanData> spans = otel.getSpans();
            assertThat(spans).hasSize(2);
            SpanData generation = spans.stream()
                    .filter(span -> !span.getName().equals("late-provider-child"))
                    .findFirst()
                    .orElseThrow();
            SpanData providerChild = spans.stream()
                    .filter(span -> span.getName().equals("late-provider-child"))
                    .findFirst()
                    .orElseThrow();
            assertThat(providerChild.getTraceId()).isEqualTo(generation.getTraceId());
            assertThat(providerChild.getParentSpanId()).isEqualTo(generation.getSpanId());
        } finally {
            rawScheduler.shutdownNow();
        }
    }

    @Test
    void publicSubmissionTimeTaskAndExecutorAdaptersCaptureEverySupportedBoundary() throws Exception {
        ScheduledExecutorService rawScheduler = Executors.newScheduledThreadPool(2);
        Executor wrappedExecutor = LangChain4jStreamingContext.taskWrapping((Executor) rawScheduler);
        ExecutorService wrappedExecutorService = LangChain4jStreamingContext.taskWrapping(
                (ExecutorService) rawScheduler);
        ScheduledExecutorService wrappedScheduler = LangChain4jStreamingContext.taskWrapping(rawScheduler);
        Span parent = otel.getOpenTelemetry().getTracer("public-context-bridge-test")
                .spanBuilder("public-context-parent")
                .startSpan();
        LangfuseTraceContext metadata = LangfuseTraceContext.builder()
                .userId("adapter-user")
                .build();

        try {
            CompletableFuture<String> wrappedRunnable = new CompletableFuture<>();
            CompletableFuture<String> executorRunnable = new CompletableFuture<>();
            Future<String> wrappedCallable;
            Future<String> serviceCallable;
            ScheduledFuture<String> scheduledCallable;

            try (Scope parentScope = parent.makeCurrent();
                 Scope metadataScope = LangfuseContext.makeCurrent(metadata)) {
                rawScheduler.execute(LangChain4jStreamingContext.wrap((Runnable) () ->
                        wrappedRunnable.complete(currentContextIdentity())));
                wrappedCallable = rawScheduler.submit(LangChain4jStreamingContext.wrap(
                        (java.util.concurrent.Callable<String>) this::currentContextIdentity));
                wrappedExecutor.execute(
                        () -> executorRunnable.complete(currentContextIdentity()));
                serviceCallable = wrappedExecutorService.submit(this::currentContextIdentity);
                scheduledCallable = wrappedScheduler.schedule(
                        this::currentContextIdentity, 1, TimeUnit.MILLISECONDS);
            }

            String expected = parent.getSpanContext().getSpanId() + ":adapter-user";
            assertThat(wrappedRunnable.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(wrappedCallable.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(executorRunnable.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(serviceCallable.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(scheduledCallable.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(rawScheduler.submit(() ->
                    !Span.current().getSpanContext().isValid()
                            && LangfuseContext.getUserId() == null)
                    .get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            parent.end();
            rawScheduler.shutdownNow();
        }
    }

    @Test
    void snapshotTaskAdmittedBeforeTerminalFinishesInFlightButLaterTasksDoNotRestore() throws Exception {
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        LateSchedulingProvider target = new LateSchedulingProvider();
        StreamingChatModel proxy = streamingProxy(target);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicReference<String> beforeTerminal = new AtomicReference<>();
        AtomicReference<String> afterTerminal = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();

        proxy.doChat(ChatRequest.builder().messages(UserMessage.from("in-flight")).build(),
                countingHandler(completions, new AtomicInteger()));
        LangChain4jStreamingContext.Snapshot snapshot = target.snapshot.get();
        ExecutorService fixedExecutor = snapshot.taskWrapping(rawExecutor);

        try {
            Future<?> inFlight = fixedExecutor.submit(() -> {
                beforeTerminal.set(Span.current().getSpanContext().getSpanId());
                taskStarted.countDown();
                try {
                    if (!releaseTask.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("in-flight task was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                afterTerminal.set(Span.current().getSpanContext().getSpanId());
            });

            assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();
            target.handler.get().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .build());
            assertThat(snapshot.isActive()).isFalse();
            assertThat(completions).hasValue(1);

            releaseTask.countDown();
            inFlight.get(5, TimeUnit.SECONDS);
            assertThat(beforeTerminal.get()).isNotEqualTo(SpanContext.getInvalid().getSpanId());
            assertThat(afterTerminal).hasValue(beforeTerminal.get());

            assertThat(fixedExecutor.submit(() ->
                    Span.current().getSpanContext().isValid()).get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(rawExecutor.submit(() ->
                    !Span.current().getSpanContext().isValid()).get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseTask.countDown();
            rawExecutor.shutdownNow();
        }
    }

    @Test
    void fixedRateAndFixedDelayTasksStopRestoringTheSnapshotAfterTerminal() throws Exception {
        ScheduledExecutorService rawScheduler = Executors.newScheduledThreadPool(2);
        LateSchedulingProvider target = new LateSchedulingProvider();
        StreamingChatModel proxy = streamingProxy(target);
        AtomicInteger completions = new AtomicInteger();

        proxy.doChat(ChatRequest.builder().messages(UserMessage.from("periodic")).build(),
                countingHandler(completions, new AtomicInteger()));
        LangChain4jStreamingContext.Snapshot snapshot = target.snapshot.get();
        ScheduledExecutorService fixedScheduler = snapshot.taskWrapping(rawScheduler);

        CountDownLatch firstRunsStarted = new CountDownLatch(2);
        CountDownLatch releaseFirstRuns = new CountDownLatch(1);
        CountDownLatch secondRunsFinished = new CountDownLatch(2);
        AtomicInteger fixedRateRuns = new AtomicInteger();
        AtomicInteger fixedDelayRuns = new AtomicInteger();
        AtomicReference<Boolean> fixedRateFirstContext = new AtomicReference<>();
        AtomicReference<Boolean> fixedRateSecondContext = new AtomicReference<>();
        AtomicReference<Boolean> fixedDelayFirstContext = new AtomicReference<>();
        AtomicReference<Boolean> fixedDelaySecondContext = new AtomicReference<>();

        ScheduledFuture<?> fixedRate = fixedScheduler.scheduleAtFixedRate(
                periodicContextProbe(fixedRateRuns, fixedRateFirstContext, fixedRateSecondContext,
                        firstRunsStarted, releaseFirstRuns, secondRunsFinished),
                0, 1, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> fixedDelay = fixedScheduler.scheduleWithFixedDelay(
                periodicContextProbe(fixedDelayRuns, fixedDelayFirstContext, fixedDelaySecondContext,
                        firstRunsStarted, releaseFirstRuns, secondRunsFinished),
                0, 1, TimeUnit.MILLISECONDS);

        try {
            assertThat(firstRunsStarted.await(5, TimeUnit.SECONDS)).isTrue();
            target.handler.get().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .build());
            assertThat(snapshot.isActive()).isFalse();

            releaseFirstRuns.countDown();
            assertThat(secondRunsFinished.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(fixedRateFirstContext).hasValue(true);
            assertThat(fixedDelayFirstContext).hasValue(true);
            assertThat(fixedRateSecondContext).hasValue(false);
            assertThat(fixedDelaySecondContext).hasValue(false);
            assertThat(completions).hasValue(1);
        } finally {
            releaseFirstRuns.countDown();
            fixedRate.cancel(true);
            fixedDelay.cancel(true);
            rawScheduler.shutdownNow();
        }
    }

    @Test
    void delegateListenersReceiveEachTerminalSignalOnceWithCallbackContext() {
        ListenerAwareDeferredProvider target = new ListenerAwareDeferredProvider();
        StreamingChatModel proxy = streamingProxy(target);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("listener-user")
                .sessionId("listener-session")
                .build();

        try (Scope ignored = LangfuseContext.makeCurrent(traceContext)) {
            proxy.chat(ChatRequest.builder().messages(UserMessage.from("complete")).build(),
                    countingHandler(completed, failed));
        }
        target.handler.get().onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("complete"))
                .build());
        target.handler.get().onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("duplicate"))
                .build());
        target.handler.get().onError(new IllegalStateException("late error"));

        try (Scope ignored = LangfuseContext.makeCurrent(traceContext)) {
            proxy.chat(ChatRequest.builder().messages(UserMessage.from("error")).build(),
                    countingHandler(completed, failed));
        }
        target.handler.get().onError(new IllegalStateException("provider error"));
        target.handler.get().onError(new IllegalStateException("duplicate error"));
        target.handler.get().onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from("late complete"))
                .build());

        assertThat(target.listener.requests).hasValue(2);
        assertThat(target.listener.responses).hasValue(1);
        assertThat(target.listener.errors).hasValue(1);
        assertThat(target.listener.callbackSpanWasCurrent).isTrue();
        assertThat(target.listener.attributeSpanMatchedCurrent).isTrue();
        assertThat(target.listener.requestAttributeUsers).containsOnly("listener-user");
        assertThat(target.listener.attributeUsers).containsOnly("listener-user");
        assertThat(target.listener.attributeSnapshot.get()).isNotNull();
        assertThat(target.listener.attributeSnapshot.get().isInvocationBound()).isTrue();
        assertThat(target.listener.attributeSnapshot.get().isActive()).isFalse();
        assertThat(completed).hasValue(1);
        assertThat(failed).hasValue(1);
        assertThat(otel.getSpans()).hasSize(2);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(LangfuseContext.getUserId()).isNull();
    }

    @Test
    void immutableListenerAttributesFailOpenWithoutSuppressingDelegateListener() {
        ListenerAwareDeferredProvider target = new ListenerAwareDeferredProvider();
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        target, new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("immutable attributes"))
                .build();
        ChatModelRequestContext requestContext = new ChatModelRequestContext(
                request, null, java.util.Collections.emptyMap());

        proxy.listeners().get(0).onRequest(requestContext);

        assertThat(target.listener.requests).hasValue(1);
    }

    @Test
    void langChain4j118CancellationEndsOnceAndSuppressesLateTerminalSignals() throws Exception {
        Assumptions.assumeTrue(classPresent(
                "dev.langchain4j.model.chat.response.PartialResponseContext"));

        DeferredCallbackLangChain4jChatModel target = new DeferredCallbackLangChain4jChatModel();
        StreamingChatModel proxy = streamingProxy(target);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<Boolean> callbackScopeRestored = new AtomicReference<>();
        StreamingChatResponseHandler cancellingHandler = runtimeCancellingHandler(
                cancellations, completed, failed);

        proxy.chat(ChatRequest.builder().messages(UserMessage.from("cancel")).build(),
                cancellingHandler);

        CompletableFuture.runAsync(() -> {
            invokePartialWithCancellation(target.handler.get(), cancellations);
            target.handler.get().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("late complete"))
                    .build());
            target.handler.get().onError(new IllegalStateException("late error"));
            callbackScopeRestored.set(!Span.current().getSpanContext().isValid());
        }).get(5, TimeUnit.SECONDS);

        assertThat(cancellations).hasValue(1);
        assertThat(completed).hasValue(0);
        assertThat(failed).hasValue(0);
        assertThat(callbackScopeRestored).hasValue(true);
        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void reentrantCancellationDoesNotDeadlockOnProviderTerminalAcknowledgement() {
        Assumptions.assumeTrue(classPresent(
                "dev.langchain4j.model.chat.response.PartialResponseContext"));

        DeferredCallbackLangChain4jChatModel target = new DeferredCallbackLangChain4jChatModel();
        StreamingChatModel proxy = streamingProxy(target);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch acknowledgementReturned = new CountDownLatch(1);
        AtomicReference<Throwable> acknowledgementFailure = new AtomicReference<>();

        proxy.chat(ChatRequest.builder().messages(UserMessage.from("cancel acknowledgement")).build(),
                runtimeCancellingHandler(cancellations, completed, failed));

        invokePartialWithBlockingCancellation(
                target.handler.get(), cancellations, acknowledgementReturned,
                acknowledgementFailure);

        assertThat(acknowledgementReturned.getCount()).isZero();
        assertThat(acknowledgementFailure).hasValue(null);
        assertThat(cancellations).hasValue(1);
        assertThat(completed).hasValue(0);
        assertThat(failed).hasValue(0);
        assertThat(otel.getSpans()).hasSize(1);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void syncChatStillWorks() {
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new StubStreamingLangChain4jChatModel(),
                        new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));

        ChatResponse response = ((ChatModel) proxy).chat(ChatRequest.builder()
                .messages(UserMessage.from("sync-test"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("gpt-4o-mini")
                        .build())
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("sync: sync-test");
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("sync: sync-test");
    }

    private void invokeWithMetadata(
            io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy,
            ChatRequest request,
            String user,
            String session,
            CompletableFuture<ChatResponse> response,
            Map<String, AtomicReference<String>> callbackSpanIds,
            Map<String, AtomicReference<String>> callbackUsers) {
        callbackSpanIds.put(user, new AtomicReference<>());
        callbackUsers.put(user, new AtomicReference<>());
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId(user)
                .sessionId(session)
                .build();
        try (Scope ignored = LangfuseContext.makeCurrent(traceContext)) {
            proxy.doChat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                }

                @Override
                public void onCompleteResponse(ChatResponse chatResponse) {
                    callbackSpanIds.get(user).set(Span.current().getSpanContext().getSpanId());
                    callbackUsers.get(user).set(LangfuseContext.getUserId());
                    response.complete(chatResponse);
                }

                @Override
                public void onError(Throwable error) {
                    response.completeExceptionally(error);
                }
            });
        }
    }

    private StreamingChatResponseHandler countingHandler(AtomicInteger completed, AtomicInteger failed) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {
                failed.incrementAndGet();
            }
        };
    }

    private String currentContextIdentity() {
        return Span.current().getSpanContext().getSpanId() + ":" + LangfuseContext.getUserId();
    }

    private Runnable periodicContextProbe(AtomicInteger runs,
                                          AtomicReference<Boolean> firstContext,
                                          AtomicReference<Boolean> secondContext,
                                          CountDownLatch firstRunsStarted,
                                          CountDownLatch releaseFirstRuns,
                                          CountDownLatch secondRunsFinished) {
        return () -> {
            int run = runs.incrementAndGet();
            boolean contextValid = Span.current().getSpanContext().isValid();
            if (run == 1) {
                firstContext.set(contextValid);
                firstRunsStarted.countDown();
                try {
                    if (!releaseFirstRuns.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("periodic task was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            } else if (run == 2) {
                secondContext.set(contextValid);
                secondRunsFinished.countDown();
            }
        };
    }

    private StreamingChatResponseHandler responseHandler(AtomicReference<ChatResponse> completedResponse) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completedResponse.set(response);
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        };
    }

    private StreamingChatResponseHandler runtimeCancellingHandler(AtomicInteger cancellations,
                                                                   AtomicInteger completed,
                                                                   AtomicInteger failed) {
        InvocationHandler invocationHandler = (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) return "CancellingHandler";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == arguments[0];
            }
            if (method.getName().equals("onCompleteResponse")) {
                completed.incrementAndGet();
                return null;
            }
            if (method.getName().equals("onError")) {
                failed.incrementAndGet();
                return null;
            }
            if (method.getName().equals("onPartialResponse")
                    && arguments != null && arguments.length == 2) {
                Object callbackContext = arguments[1];
                Object handle = callbackContext.getClass().getMethod("streamingHandle")
                        .invoke(callbackContext);
                Method cancel = handle.getClass().getMethod("cancel");
                cancel.invoke(handle);
                cancel.invoke(handle);
            }
            return null;
        };
        return (StreamingChatResponseHandler) Proxy.newProxyInstance(
                StreamingChatResponseHandler.class.getClassLoader(),
                new Class<?>[]{StreamingChatResponseHandler.class},
                invocationHandler);
    }

    private void invokePartialWithCancellation(StreamingChatResponseHandler handler,
                                               AtomicInteger cancellations) {
        try {
            ClassLoader classLoader = StreamingChatResponseHandler.class.getClassLoader();
            Class<?> partialType = Class.forName(
                    "dev.langchain4j.model.chat.response.PartialResponse", true, classLoader);
            Class<?> contextType = Class.forName(
                    "dev.langchain4j.model.chat.response.PartialResponseContext", true, classLoader);
            Class<?> handleType = Class.forName(
                    "dev.langchain4j.model.chat.response.StreamingHandle", true, classLoader);
            Object handle = Proxy.newProxyInstance(classLoader, new Class<?>[]{handleType},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("cancel")) {
                            cancellations.incrementAndGet();
                            return null;
                        }
                        if (method.getName().equals("isCancelled")) {
                            return cancellations.get() > 0;
                        }
                        if (method.getName().equals("toString")) return "ProviderStreamingHandle";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == arguments[0];
                        return null;
                    });
            Object partial = partialType.getConstructor(String.class).newInstance("cancel-token");
            Object callbackContext = contextType.getConstructor(handleType).newInstance(handle);
            Method callback = StreamingChatResponseHandler.class.getMethod(
                    "onPartialResponse", partialType, contextType);
            callback.invoke(handler, partial, callbackContext);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private void invokePartialWithBlockingCancellation(
            StreamingChatResponseHandler handler,
            AtomicInteger cancellations,
            CountDownLatch acknowledgementReturned,
            AtomicReference<Throwable> acknowledgementFailure) {
        try {
            ClassLoader classLoader = StreamingChatResponseHandler.class.getClassLoader();
            Class<?> partialType = Class.forName(
                    "dev.langchain4j.model.chat.response.PartialResponse", true, classLoader);
            Class<?> contextType = Class.forName(
                    "dev.langchain4j.model.chat.response.PartialResponseContext", true, classLoader);
            Class<?> handleType = Class.forName(
                    "dev.langchain4j.model.chat.response.StreamingHandle", true, classLoader);
            Object handle = Proxy.newProxyInstance(classLoader, new Class<?>[]{handleType},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("cancel")) {
                            if (cancellations.incrementAndGet() != 1) return null;
                            Thread acknowledgement = new Thread(() -> {
                                try {
                                    handler.onCompleteResponse(ChatResponse.builder()
                                            .aiMessage(AiMessage.from("provider acknowledgement"))
                                            .build());
                                } catch (Throwable failure) {
                                    acknowledgementFailure.set(failure);
                                } finally {
                                    acknowledgementReturned.countDown();
                                }
                            }, "langchain4j-terminal-acknowledgement-test");
                            acknowledgement.start();
                            if (!acknowledgementReturned.await(2, TimeUnit.SECONDS)) {
                                throw new AssertionError(
                                        "provider terminal acknowledgement was blocked by callback serialization");
                            }
                            return null;
                        }
                        if (method.getName().equals("isCancelled")) {
                            return cancellations.get() > 0;
                        }
                        if (method.getName().equals("toString")) return "BlockingProviderStreamingHandle";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == arguments[0];
                        return null;
                    });
            Object partial = partialType.getConstructor(String.class).newInstance("cancel-token");
            Object callbackContext = contextType.getConstructor(handleType).newInstance(handle);
            Method callback = StreamingChatResponseHandler.class.getMethod(
                    "onPartialResponse", partialType, contextType);
            callback.invoke(handler, partial, callbackContext);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private boolean classPresent(String className) {
        try {
            Class.forName(className, false, StreamingChatResponseHandler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private StreamingChatModel streamingProxy(Object target) {
        return streamingProxy(target, new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    private StreamingChatModel streamingProxy(Object target, LangfuseOtel langfuseOtel) {
        return new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                target,
                langfuseOtel);
    }

    static class FailingContext implements Context {
        private final int failingWrite;
        private final AtomicInteger writes = new AtomicInteger();

        FailingContext(int failingWrite) {
            this.failingWrite = failingWrite;
        }

        @Override
        public <V> V get(ContextKey<V> key) {
            return Context.root().get(key);
        }

        @Override
        public <V> Context with(ContextKey<V> key, V value) {
            if (writes.incrementAndGet() == failingWrite) {
                throw new IllegalStateException("context state storage unavailable");
            }
            return this;
        }
    }

    static class FailingSecondCapturePolicyLangfuseOtel extends LangfuseOtel {
        private final AtomicInteger policyReads = new AtomicInteger();

        FailingSecondCapturePolicyLangfuseOtel(OpenTelemetry openTelemetry) {
            super(null, openTelemetry, null, true);
        }

        @Override
        public ContentCapturePolicy getContentCapturePolicy() {
            if (policyReads.incrementAndGet() == 2) {
                throw new IllegalStateException("capture policy unavailable");
            }
            return ContentCapturePolicy.captureAll();
        }
    }

    static class ContextAwareAsyncProvider implements StreamingChatModel {
        private final ExecutorService executor;
        private final Tracer tracer;
        private final CountDownLatch tasksCompleted;
        private final Map<String, String> workerUsers = new ConcurrentHashMap<>();
        private final Map<String, String> workerSessions = new ConcurrentHashMap<>();

        ContextAwareAsyncProvider(ExecutorService executor, Tracer tracer, int requests) {
            this.executor = executor;
            this.tracer = tracer;
            this.tasksCompleted = new CountDownLatch(requests);
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            LangChain4jStreamingContext.Snapshot snapshot = LangChain4jStreamingContext.capture();
            LangfuseTraceContext schedulingContext = snapshot.traceContext();
            String user = schedulingContext.getUserId();
            executor.execute(snapshot.wrap(() -> {
                Span providerChild = null;
                try {
                    workerUsers.put(user, LangfuseContext.getUserId());
                    workerSessions.put(user, LangfuseContext.getSessionId());
                    providerChild = tracer.spanBuilder("provider-child-" + user).startSpan();
                    try (Scope ignored = providerChild.makeCurrent()) {
                        handler.onPartialResponse(user);
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from(user))
                                .build());
                    }
                } catch (Throwable failure) {
                    handler.onError(failure);
                } finally {
                    if (providerChild != null) providerChild.end();
                    tasksCompleted.countDown();
                }
            }));
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("async-provider").build();
        }
    }

    static class LateSchedulingProvider implements StreamingChatModel {
        private final AtomicReference<LangChain4jStreamingContext.Snapshot> snapshot =
                new AtomicReference<>();
        private final AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            snapshot.set(LangChain4jStreamingContext.capture());
            this.handler.set(handler);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("late-provider").build();
        }
    }

    static class ListenerAwareDeferredProvider implements StreamingChatModel {
        private final AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        private final CapturingChatModelListener listener = new CapturingChatModelListener();

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            this.handler.set(handler);
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of(listener);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("listener-provider").build();
        }
    }

    static class CapturingChatModelListener implements ChatModelListener {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger responses = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();
        private final AtomicBoolean callbackSpanWasCurrent = new AtomicBoolean();
        private final AtomicBoolean attributeSpanMatchedCurrent = new AtomicBoolean();
        private final java.util.Set<String> requestAttributeUsers = ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> attributeUsers = ConcurrentHashMap.newKeySet();
        private final AtomicReference<LangChain4jStreamingContext.Snapshot> attributeSnapshot =
                new AtomicReference<>();

        @Override
        public void onRequest(ChatModelRequestContext requestContext) {
            requests.incrementAndGet();
            LangfuseTraceContext traceContext =
                    LangChain4jStreamingContext.traceContext(requestContext.attributes());
            if (traceContext != null && traceContext.getUserId() != null) {
                requestAttributeUsers.add(traceContext.getUserId());
            }
        }

        @Override
        public void onResponse(ChatModelResponseContext responseContext) {
            responses.incrementAndGet();
            capture(responseContext.attributes());
        }

        @Override
        public void onError(ChatModelErrorContext errorContext) {
            errors.incrementAndGet();
            capture(errorContext.attributes());
        }

        private void capture(Map<Object, Object> attributes) {
            attributeSnapshot.set(LangChain4jStreamingContext.capture(attributes));
            Span current = Span.current();
            callbackSpanWasCurrent.set(current.getSpanContext().isValid());
            io.opentelemetry.context.Context attributeContext =
                    LangChain4jStreamingContext.context(attributes);
            attributeSpanMatchedCurrent.set(attributeContext != null
                    && Span.fromContext(attributeContext).getSpanContext().getSpanId()
                    .equals(current.getSpanContext().getSpanId()));
            LangfuseTraceContext traceContext =
                    LangChain4jStreamingContext.traceContext(attributes);
            if (traceContext != null && traceContext.getUserId() != null) {
                attributeUsers.add(traceContext.getUserId());
            }
        }
    }

    static class StubStreamingLangChain4jChatModel implements StreamingChatModel, ChatModel {

        @Override
        public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
            return java.util.Set.of();
        }

        @Override
        public dev.langchain4j.model.ModelProvider provider() {
            return null;
        }

        @Override
        public java.util.List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
            return java.util.List.of();
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("Hello");
            handler.onPartialResponse("World");
            handler.onCompleteResponse(ChatResponse.builder()
                    .modelName("gpt-4o-mini")
                    .aiMessage(AiMessage.from("HelloWorld"))
                    .tokenUsage(new TokenUsage(4, 5, 9))
                    .build());
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            java.util.List<ChatMessage> messages = chatRequest.messages();
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            String text = lastMessage instanceof UserMessage
                    ? ((UserMessage) lastMessage).singleText()
                    : String.valueOf(lastMessage);

            return ChatResponse.builder()
                    .modelName("gpt-4o-mini")
                    .tokenUsage(new TokenUsage(4, 5, 9))
                    .aiMessage(AiMessage.from("sync: " + text))
                    .build();
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder()
                    .modelName("gpt-4o-mini")
                    .temperature(0.3)
                    .topP(0.8)
                    .maxOutputTokens(128)
                    .build();
        }
    }

    @Test
    void streamingDoesNotFailWhenDelegateThrows() {
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new ThrowingStreamingLangChain4jChatModel(),
                        new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                ((StreamingChatModel) proxy).chat(ChatRequest.builder()
                        .messages(UserMessage.from("test"))
                        .build(), new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String p) {}
                    @Override public void onCompleteResponse(ChatResponse r) {}
                    @Override public void onError(Throwable e) {}
                }));

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    @Test
    void syncChatOnStreamingOnlyDelegateThrowsUnsupported() {
        io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel proxy =
                new io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel(
                        new StreamingOnlyLangChain4jChatModel(),
                        new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () ->
                ((dev.langchain4j.model.chat.ChatModel) proxy).chat(ChatRequest.builder()
                        .messages(UserMessage.from("test"))
                        .build()));
    }

    static class ThrowingStreamingLangChain4jChatModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            throw new RuntimeException("delegate exploded");
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("gpt-4o-mini").build();
        }
    }

    static class StreamingOnlyLangChain4jChatModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("token");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("token")).build());
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("gpt-4o-mini").build();
        }
    }

    static class ErrorStreamingLangChain4jChatModel implements StreamingChatModel {

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onError(new RuntimeException("streaming error"));
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder()
                    .modelName("gpt-4o-mini")
                    .build();
        }
    }

    static class DeferredCallbackLangChain4jChatModel implements StreamingChatModel {
        private final AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            this.handler.set(handler);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder()
                    .modelName("gpt-4o-mini")
                    .build();
        }
    }

    static class SetupFailureStreamingLangChain4jChatModel implements StreamingChatModel {

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("fallback response"))
                    .build());
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            throw new IllegalStateException("parameters unavailable");
        }
    }
}
