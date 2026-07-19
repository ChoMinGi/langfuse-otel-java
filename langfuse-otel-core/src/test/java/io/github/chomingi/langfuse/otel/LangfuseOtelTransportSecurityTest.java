package io.github.chomingi.langfuse.otel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
