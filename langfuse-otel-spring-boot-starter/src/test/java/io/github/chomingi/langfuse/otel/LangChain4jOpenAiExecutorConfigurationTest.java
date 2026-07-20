package io.github.chomingi.langfuse.otel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.chomingi.langfuse.otel.spring.TracingStreamingLangChain4jChatModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LangChain4jOpenAiExecutorConfigurationTest {

    private static final byte[] STREAM_RESPONSE = (
            "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":0,\"model\":\"gpt-4o-mini\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                    + "\"content\":\"hello\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":0,\"model\":\"gpt-4o-mini\","
                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                    + "\"total_tokens\":2}}\n\n"
                    + "data: [DONE]\n\n")
            .getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void tracingWrapperRestoresCallbacksForOpenAiWithConfiguredJdkExecutor() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer receiver = startReceiver(requestPath);
        ThreadPoolExecutor providerExecutor = providerExecutor();

        try {
            assertThat(providerExecutor.submit(
                    LangChain4jOpenAiExecutorConfigurationTest::currentThreadIsClean)
                    .get(5, TimeUnit.SECONDS)).isTrue();
            long tasksBeforeRequest = providerExecutor.getTaskCount();

            StreamingChatModel model = new TracingStreamingLangChain4jChatModel(
                    openAiModel(receiver, providerExecutor),
                    new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
            LangfuseTraceContext metadata = LangfuseTraceContext.builder()
                    .userId("provider-user")
                    .build();
            CallbackCapture callbacks = new CallbackCapture();

            try (Scope metadataScope = LangfuseContext.makeCurrent(metadata)) {
                model.chat("hello", callbacks);
            }

            assertThat(callbacks.awaitTerminal()).isTrue();
            assertThat(callbacks.failure).hasValue(null);
            assertThat(requestPath).hasValue("/v1/chat/completions");
            assertThat(callbacks.partial).hasValue("hello");
            assertThat(callbacks.partialContext.get().isValid()).isTrue();
            assertThat(callbacks.completionContext).hasValue(callbacks.partialContext.get());
            assertThat(callbacks.partialUser).hasValue("provider-user");
            assertThat(callbacks.completionUser).hasValue("provider-user");

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(otel.getSpans())
                            .singleElement()
                            .extracting(SpanData::getSpanId)
                            .isEqualTo(callbacks.completionContext.get().getSpanId()));
            assertThat(providerExecutor.getTaskCount()).isGreaterThan(tasksBeforeRequest);
            assertThat(providerExecutor.submit(
                    LangChain4jOpenAiExecutorConfigurationTest::currentThreadIsClean)
                    .get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            providerExecutor.shutdownNow();
            receiver.stop(0);
        }
    }

    private static StreamingChatModel openAiModel(HttpServer receiver, Executor executor) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("http://127.0.0.1:" + receiver.getAddress().getPort() + "/v1")
                .apiKey("test-key")
                .modelName("gpt-4o-mini")
                .timeout(Duration.ofSeconds(5))
                .httpClientBuilder(JdkHttpClient.builder()
                        .httpClientBuilder(HttpClient.newBuilder()
                                .executor(executor)))
                .build();
    }

    private static ThreadPoolExecutor providerExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "langchain4j-openai-http");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static boolean currentThreadIsClean() {
        return !Span.current().getSpanContext().isValid()
                && LangfuseContext.getUserId() == null;
    }

    private static HttpServer startReceiver(AtomicReference<String> requestPath) throws IOException {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            sendStream(exchange);
        });
        receiver.start();
        return receiver;
    }

    private static void sendStream(HttpExchange exchange) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, STREAM_RESPONSE.length);
            exchange.getResponseBody().write(STREAM_RESPONSE);
        } finally {
            exchange.close();
        }
    }

    private static final class CallbackCapture implements StreamingChatResponseHandler {
        private final AtomicReference<String> partial = new AtomicReference<>();
        private final AtomicReference<SpanContext> partialContext = new AtomicReference<>();
        private final AtomicReference<SpanContext> completionContext = new AtomicReference<>();
        private final AtomicReference<String> partialUser = new AtomicReference<>();
        private final AtomicReference<String> completionUser = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onPartialResponse(String text) {
            partial.set(text);
            partialContext.set(Span.current().getSpanContext());
            partialUser.set(LangfuseContext.getUserId());
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            completionContext.set(Span.current().getSpanContext());
            completionUser.set(LangfuseContext.getUserId());
            terminal.countDown();
        }

        @Override
        public void onError(Throwable error) {
            failure.set(error);
            terminal.countDown();
        }

        private boolean awaitTerminal() throws InterruptedException {
            return terminal.await(5, TimeUnit.SECONDS);
        }
    }
}
