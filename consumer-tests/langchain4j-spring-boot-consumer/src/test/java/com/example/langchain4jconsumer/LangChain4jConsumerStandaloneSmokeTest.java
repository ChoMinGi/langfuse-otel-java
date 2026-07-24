package com.example.langchain4jconsumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = LangChain4jConsumerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class LangChain4jConsumerStandaloneSmokeTest {

    private static final String PUBLIC_KEY = "pk-langchain4j-consumer";
    private static final String SECRET_KEY = "sk-langchain4j-consumer";
    private static final String SERVICE_NAME = "langchain4j-consumer-smoke";
    private static final LoopbackOtlpReceiver RECEIVER = LoopbackOtlpReceiver.start();

    private final ChatModel chatModel;
    private final LangfuseOtel langfuseOtel;

    @Autowired
    LangChain4jConsumerStandaloneSmokeTest(ChatModel chatModel, LangfuseOtel langfuseOtel) {
        this.chatModel = chatModel;
        this.langfuseOtel = langfuseOtel;
    }

    @DynamicPropertySource
    static void standaloneProperties(DynamicPropertyRegistry properties) {
        properties.add("langfuse.otel-mode", () -> "standalone");
        properties.add("langfuse.public-key", () -> PUBLIC_KEY);
        properties.add("langfuse.secret-key", () -> SECRET_KEY);
        properties.add("langfuse.host", RECEIVER::host);
        properties.add("langfuse.service-name", () -> SERVICE_NAME);
        properties.add("langfuse.allow-insecure-http-for-development", () -> "true");
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.close();
    }

    @Test
    void bindsStandalonePropertiesAndExportsTheAutomaticallyInstrumentedModelSpan() throws Exception {
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(UserMessage.from("standalone consumer smoke"))
                .parameters(DefaultChatRequestParameters.builder()
                        .modelName("langchain4j-smoke-model")
                        .build())
                .build());

        assertThat(langfuseOtel.isNoop()).isFalse();
        assertThat(langfuseOtel.getOpenTelemetryOwnership())
                .isEqualTo(LangfuseOtel.OpenTelemetryOwnership.OWNED);
        assertThat(response.aiMessage().text())
                .isEqualTo("langchain4j response: standalone consumer smoke");

        langfuseOtel.flush();

        CapturedRequest request = RECEIVER.awaitRequest();
        assertThat(request.method).isEqualTo("POST");
        assertThat(request.path).isEqualTo("/api/public/otel/v1/traces");
        assertThat(request.authorization).isEqualTo(expectedAuthorization());
        assertThat(request.ingestionVersion).isEqualTo("4");
        assertThat(request.contentType).startsWith("application/x-protobuf");
        assertThat(request.body).isNotEmpty();

        ExportTraceServiceRequest export = ExportTraceServiceRequest.parseFrom(request.body);
        List<ResourceSpans> resourceSpans = export.getResourceSpansList();
        assertThat(resourceSpans)
                .extracting(group -> stringAttribute(
                        group.getResource().getAttributesList(), "service.name"))
                .containsOnly(SERVICE_NAME);

        assertThat(spans(resourceSpans)).singleElement().satisfies(span -> {
            assertThat(stringAttribute(span, LangfuseAttributes.GEN_AI_OPERATION_NAME))
                    .isEqualTo("chat");
            assertThat(stringAttribute(span, LangfuseAttributes.GEN_AI_SYSTEM))
                    .isEqualTo("langchain4j");
            assertThat(stringAttribute(span, LangfuseAttributes.GEN_AI_REQUEST_MODEL))
                    .isEqualTo("langchain4j-smoke-model");
            assertThat(stringAttribute(span, LangfuseAttributes.GEN_AI_RESPONSE_MODEL))
                    .isEqualTo("langchain4j-smoke-model");
            assertThat(longAttribute(span, LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS))
                    .isEqualTo(4L);
            assertThat(longAttribute(span, LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS))
                    .isEqualTo(5L);
            assertThat(longAttribute(span, LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS))
                    .isEqualTo(9L);
        });
    }

    private static String expectedAuthorization() {
        String credentials = PUBLIC_KEY + ":" + SECRET_KEY;
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Span> spans(List<ResourceSpans> resourceSpans) {
        return resourceSpans.stream()
                .flatMap(group -> group.getScopeSpansList().stream())
                .flatMap(scope -> scope.getSpansList().stream())
                .collect(Collectors.toList());
    }

    private static String stringAttribute(Span span, String key) {
        return stringAttribute(span.getAttributesList(), key);
    }

    private static String stringAttribute(List<KeyValue> attributes, String key) {
        AnyValue value = attribute(attributes, key);
        assertThat(value.getValueCase()).as("attribute %s type", key)
                .isEqualTo(AnyValue.ValueCase.STRING_VALUE);
        return value.getStringValue();
    }

    private static long longAttribute(Span span, String key) {
        AnyValue value = attribute(span.getAttributesList(), key);
        assertThat(value.getValueCase()).as("attribute %s type", key)
                .isEqualTo(AnyValue.ValueCase.INT_VALUE);
        return value.getIntValue();
    }

    private static AnyValue attribute(List<KeyValue> attributes, String key) {
        return attributes.stream()
                .filter(item -> item.getKey().equals(key))
                .map(KeyValue::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing OTLP attribute: " + key));
    }

    private static final class LoopbackOtlpReceiver implements AutoCloseable {
        private final HttpServer server;
        private final BlockingQueue<CapturedRequest> requests = new ArrayBlockingQueue<>(1);

        private LoopbackOtlpReceiver(HttpServer server) {
            this.server = server;
        }

        private static LoopbackOtlpReceiver start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                LoopbackOtlpReceiver receiver = new LoopbackOtlpReceiver(server);
                server.createContext("/", receiver::capture);
                server.start();
                return receiver;
            } catch (IOException e) {
                throw new IllegalStateException("Could not start loopback OTLP receiver", e);
            }
        }

        private String host() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private CapturedRequest awaitRequest() throws InterruptedException {
            CapturedRequest request = requests.poll(5, TimeUnit.SECONDS);
            assertThat(request).as("standalone OTLP request").isNotNull();
            return request;
        }

        private void capture(HttpExchange exchange) throws IOException {
            try {
                requests.offer(CapturedRequest.from(exchange));
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class CapturedRequest {
        private final String method;
        private final String path;
        private final String authorization;
        private final String ingestionVersion;
        private final String contentType;
        private final byte[] body;

        private CapturedRequest(String method, String path, String authorization,
                                String ingestionVersion, String contentType, byte[] body) {
            this.method = method;
            this.path = path;
            this.authorization = authorization;
            this.ingestionVersion = ingestionVersion;
            this.contentType = contentType;
            this.body = body;
        }

        private static CapturedRequest from(HttpExchange exchange) throws IOException {
            return new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestBody().readAllBytes());
        }
    }
}
