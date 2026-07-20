package io.github.chomingi.langfuse.otel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseOtelTransportSecurityTest {

    private static final String OTEL_PATH = "/api/public/otel/v1/traces";
    private static final String PUBLIC_KEY = "pk-local-contract";
    private static final String SECRET_KEY = "sk-local-contract";
    private static final String EXPECTED_AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString((PUBLIC_KEY + ":" + SECRET_KEY).getBytes(StandardCharsets.UTF_8));

    @Test
    void standaloneExporterSendsExpectedOtlpContractToExplicitDevelopmentHttpEndpoint() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer receiver = startServer(exchange -> {
            captured.set(CapturedRequest.from(exchange));
            sendEmptyProtobufResponse(exchange, 200);
            received.countDown();
        });

        try {
            String host = loopbackHost(receiver) + "/self-hosted/";
            try (LangfuseOtel langfuse = standaloneBuilder(host)
                    .allowInsecureHttpForDevelopment(true)
                    .build()) {
                langfuse.trace("transport-contract-span", trace -> {});
                langfuse.flush();
            }

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            CapturedRequest request = captured.get();
            assertThat(request).isNotNull();
            assertThat(request.method).isEqualTo("POST");
            assertThat(request.path).isEqualTo("/self-hosted" + OTEL_PATH);
            assertThat(request.authorization).isEqualTo(EXPECTED_AUTHORIZATION);
            assertThat(request.ingestionVersion).isEqualTo("4");
            assertThat(request.contentType).startsWith("application/x-protobuf");
            assertThat(request.body).isNotEmpty();
            assertThat(containsUtf8(request.body, "transport-contract-span")).isTrue();
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void standaloneExporterPreservesMultiSpanHierarchyAndRepresentativeAttributes() throws Exception {
        List<CapturedRequest> captured = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer receiver = startServer(exchange -> {
            try {
                captured.add(CapturedRequest.from(exchange));
                sendEmptyProtobufResponse(exchange, 200);
            } finally {
                received.countDown();
            }
        });

        try {
            try (LangfuseOtel langfuse = standaloneBuilder(loopbackHost(receiver))
                    .allowInsecureHttpForDevelopment(true)
                    .build()) {
                langfuse.trace("contract-trace", trace -> {
                    trace.userId("user-42")
                            .sessionId("session-7")
                            .tags("contract", "0.2");
                    trace.span("retrieve-context", span -> {
                        span.input("question")
                                .metadata("source", "catalog");
                        span.generation("answer", generation -> generation
                                .model("gpt-4o-mini")
                                .totalTokens(19));
                    });
                });
                langfuse.flush();
            }

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(captured).isNotEmpty();

            List<ExportTraceServiceRequest> exports = decodeExports(captured);
            List<ResourceSpans> resourceSpans = resourceSpans(exports);
            assertThat(resourceSpans)
                    .extracting(group -> stringAttribute(group.getResource().getAttributesList(), "service.name"))
                    .containsOnly("transport-contract-test");

            List<Span> spans = spans(resourceSpans);
            assertThat(spans)
                    .extracting(Span::getName)
                    .containsExactlyInAnyOrder("contract-trace", "retrieve-context", "answer");

            Span trace = spanNamed(spans, "contract-trace");
            Span retrieval = spanNamed(spans, "retrieve-context");
            Span generation = spanNamed(spans, "answer");

            assertThat(trace.getTraceId()).hasSize(16);
            assertThat(retrieval.getTraceId()).isEqualTo(trace.getTraceId());
            assertThat(generation.getTraceId()).isEqualTo(trace.getTraceId());
            assertThat(List.of(trace.getSpanId(), retrieval.getSpanId(), generation.getSpanId()))
                    .allSatisfy(spanId -> assertThat(spanId).hasSize(8))
                    .doesNotHaveDuplicates();
            assertThat(trace.getParentSpanId()).isEmpty();
            assertThat(retrieval.getParentSpanId()).isEqualTo(trace.getSpanId());
            assertThat(generation.getParentSpanId()).isEqualTo(retrieval.getSpanId());
            assertThat(trace.getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_INTERNAL);
            assertThat(retrieval.getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_INTERNAL);
            assertThat(generation.getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_CLIENT);

            assertThat(stringAttribute(trace, LangfuseAttributes.TRACE_NAME)).isEqualTo("contract-trace");
            assertThat(stringAttribute(trace, LangfuseAttributes.TRACE_USER_ID)).isEqualTo("user-42");
            assertThat(stringAttribute(trace, LangfuseAttributes.TRACE_SESSION_ID)).isEqualTo("session-7");
            assertThat(stringArrayAttribute(trace, LangfuseAttributes.TRACE_TAGS))
                    .containsExactly("contract", "0.2");

            assertThat(stringAttribute(retrieval, LangfuseAttributes.OBSERVATION_INPUT))
                    .isEqualTo("question");
            assertThat(stringAttribute(retrieval, LangfuseAttributes.OBSERVATION_METADATA + ".source"))
                    .isEqualTo("catalog");

            assertThat(stringAttribute(generation, LangfuseAttributes.GEN_AI_OPERATION_NAME)).isEqualTo("chat");
            assertThat(stringAttribute(generation, LangfuseAttributes.GEN_AI_REQUEST_MODEL))
                    .isEqualTo("gpt-4o-mini");
            assertThat(longAttribute(generation, LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS)).isEqualTo(19);
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void insecureHttpRequiresExplicitDevelopmentOptIn() {
        assertThatThrownBy(() -> standaloneBuilder("http://127.0.0.1:4318")
                .failSafe(false)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowInsecureHttpForDevelopment(true)");

        try (LangfuseOtel langfuse = standaloneBuilder("http://127.0.0.1:4318").build()) {
            assertThat(langfuse.isNoop()).isTrue();
        }
    }

    @Test
    void developmentHttpOptInStillRejectsRemoteHosts() {
        for (String host : List.of("http://example.com", "http://192.168.1.10:4318")) {
            assertThatThrownBy(() -> standaloneBuilder(host)
                    .allowInsecureHttpForDevelopment(true)
                    .failSafe(false)
                    .build())
                    .as("remote development host %s must be rejected", host)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback");
        }
    }

    @Test
    void standaloneHostRejectsUnsafeOrAmbiguousUris() {
        List<String> invalidHosts = List.of(
                "",
                "ftp://langfuse.example.com",
                "https:///missing-host",
                "https://user:password@langfuse.example.com",
                "https://langfuse.example.com?target=other",
                "https://langfuse.example.com#fragment",
                "langfuse.example.com",
                "https://langfuse.example.com:65536");

        for (String host : invalidHosts) {
            assertThatThrownBy(() -> standaloneBuilder(host)
                    .failSafe(false)
                    .build())
                    .as("host %s must be rejected", host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void crossOriginRedirectDoesNotForwardBasicAuthorization() throws Exception {
        AtomicReference<CapturedRequest> redirectedRequest = new AtomicReference<>();
        CountDownLatch redirectReceived = new CountDownLatch(1);
        HttpServer target = startServer(exchange -> {
            redirectedRequest.set(CapturedRequest.from(exchange));
            sendEmptyProtobufResponse(exchange, 200);
            redirectReceived.countDown();
        });

        AtomicReference<String> sourceAuthorization = new AtomicReference<>();
        HttpServer source = startServer(exchange -> {
            sourceAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", loopbackHost(target) + "/redirect-target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });

        try {
            try (LangfuseOtel langfuse = standaloneBuilder(loopbackHost(source))
                    .allowInsecureHttpForDevelopment(true)
                    .build()) {
                langfuse.trace("redirect-credential-test", trace -> {});
                langfuse.flush();
            }

            assertThat(redirectReceived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sourceAuthorization.get()).isEqualTo(EXPECTED_AUTHORIZATION);
            assertThat(redirectedRequest.get()).isNotNull();
            assertThat(redirectedRequest.get().authorization).isNull();
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }

    private static LangfuseOtel.Builder standaloneBuilder(String host) {
        return LangfuseOtel.builder()
                .publicKey(PUBLIC_KEY)
                .secretKey(SECRET_KEY)
                .host(host)
                .serviceName("transport-contract-test");
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static String loopbackHost(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void sendEmptyProtobufResponse(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
        exchange.sendResponseHeaders(status, 0);
        exchange.getResponseBody().close();
        exchange.close();
    }

    private static boolean containsUtf8(byte[] payload, String expected) {
        byte[] needle = expected.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= payload.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (payload[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static List<ExportTraceServiceRequest> decodeExports(List<CapturedRequest> requests)
            throws IOException {
        List<ExportTraceServiceRequest> exports = new ArrayList<>();
        for (CapturedRequest request : requests) {
            exports.add(ExportTraceServiceRequest.parseFrom(request.body));
        }
        return exports;
    }

    private static List<ResourceSpans> resourceSpans(List<ExportTraceServiceRequest> exports) {
        return exports.stream()
                .flatMap(export -> export.getResourceSpansList().stream())
                .collect(Collectors.toList());
    }

    private static List<Span> spans(List<ResourceSpans> resourceSpans) {
        return resourceSpans.stream()
                .flatMap(group -> group.getScopeSpansList().stream())
                .flatMap(scope -> scope.getSpansList().stream())
                .collect(Collectors.toList());
    }

    private static Span spanNamed(List<Span> spans, String name) {
        List<Span> matches = spans.stream()
                .filter(span -> span.getName().equals(name))
                .collect(Collectors.toList());
        assertThat(matches).as("span named %s", name).hasSize(1);
        return matches.get(0);
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

    private static List<String> stringArrayAttribute(Span span, String key) {
        AnyValue value = attribute(span.getAttributesList(), key);
        assertThat(value.getValueCase()).as("attribute %s type", key)
                .isEqualTo(AnyValue.ValueCase.ARRAY_VALUE);
        return value.getArrayValue().getValuesList().stream()
                .map(item -> {
                    assertThat(item.getValueCase()).as("attribute %s item type", key)
                            .isEqualTo(AnyValue.ValueCase.STRING_VALUE);
                    return item.getStringValue();
                })
                .collect(Collectors.toList());
    }

    private static AnyValue attribute(List<KeyValue> attributes, String key) {
        return attributes.stream()
                .filter(attribute -> attribute.getKey().equals(key))
                .map(KeyValue::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing OTLP attribute: " + key));
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
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
