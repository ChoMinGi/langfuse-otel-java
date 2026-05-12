package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseReactiveContextFilterTest {

    @AfterEach
    void cleanup() {
        LangfuseContext.clear();
    }

    @Test
    void filterSetsEnvironmentAndClearsAfterwards() {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();
        properties.setEnvironment("staging");

        LangfuseReactiveContextFilter filter = new LangfuseReactiveContextFilter(properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        AtomicReference<String> capturedEnv = new AtomicReference<>();

        WebFilterChain chain = ex -> {
            capturedEnv.set(LangfuseContext.getEnvironment());
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedEnv.get()).isEqualTo("staging");
        assertThat(LangfuseContext.getEnvironment()).isNull();
    }

    @Test
    void filterExtractsSessionId() {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();

        LangfuseReactiveContextFilter filter = new LangfuseReactiveContextFilter(properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        // Force session creation
        exchange.getSession().block();

        AtomicReference<String> capturedSessionId = new AtomicReference<>();

        WebFilterChain chain = ex -> {
            capturedSessionId.set(LangfuseContext.getSessionId());
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedSessionId.get()).isNotNull().isNotEmpty();
        assertThat(LangfuseContext.getSessionId()).isNull();
    }

    @Test
    void filterHandlesNoPrincipalGracefully() {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();

        LangfuseReactiveContextFilter filter = new LangfuseReactiveContextFilter(properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        AtomicReference<String> capturedUserId = new AtomicReference<>();

        WebFilterChain chain = ex -> {
            capturedUserId.set(LangfuseContext.getUserId());
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedUserId.get()).isNull();
    }
}
