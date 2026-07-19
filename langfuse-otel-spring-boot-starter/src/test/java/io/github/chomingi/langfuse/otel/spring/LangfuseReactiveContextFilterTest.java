package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

        AtomicReference<LangfuseTraceContext> capturedContext = new AtomicReference<>();

        WebFilterChain chain = ex -> Mono.deferContextual(contextView -> {
            capturedContext.set(contextView.get(LangfuseContext.reactorContextKey()));
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedContext.get().getEnvironment()).isEqualTo("staging");
        assertThat(LangfuseContext.getEnvironment()).isNull();
    }

    @Test
    void filterExtractsSessionId() {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();
        properties.getContext().setCaptureSessionId(true);

        LangfuseReactiveContextFilter filter = new LangfuseReactiveContextFilter(properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        // Force session creation
        exchange.getSession().block();

        AtomicReference<LangfuseTraceContext> capturedContext = new AtomicReference<>();

        WebFilterChain chain = ex -> Mono.deferContextual(contextView -> {
            capturedContext.set(contextView.get(LangfuseContext.reactorContextKey()));
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedContext.get().getSessionId()).isNotNull().isNotEmpty();
        assertThat(LangfuseContext.getSessionId()).isNull();
    }

    @Test
    void filterHandlesNoPrincipalGracefully() {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();

        LangfuseReactiveContextFilter filter = new LangfuseReactiveContextFilter(properties);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        AtomicReference<LangfuseTraceContext> capturedContext = new AtomicReference<>();

        WebFilterChain chain = ex -> Mono.deferContextual(contextView -> {
            capturedContext.set(contextView.get(LangfuseContext.reactorContextKey()));
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(capturedContext.get().getUserId()).isNull();
        assertThat(LangfuseContext.getUserId()).isNull();
    }

    @Test
    void concurrentRequestsKeepIndependentContextAcrossSchedulerSwitches() {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();
        properties.setEnvironment("production");
        properties.getContext().setCaptureSessionId(true);
        LangfuseReactiveContextFilter filter = new LangfuseReactiveContextFilter(properties);

        MockServerWebExchange first = exchangeWithSession("/first");
        MockServerWebExchange second = exchangeWithSession("/second");
        Map<String, LangfuseTraceContext> captured = new ConcurrentHashMap<>();

        WebFilterChain chain = exchange -> Mono.delay(Duration.ofMillis(5))
                .publishOn(Schedulers.parallel())
                .then(Mono.deferContextual(contextView -> {
                    captured.put(exchange.getRequest().getPath().value(),
                            contextView.get(LangfuseContext.reactorContextKey()));
                    return Mono.empty();
                }));

        StepVerifier.create(Mono.when(filter.filter(first, chain), filter.filter(second, chain)))
                .verifyComplete();

        assertThat(captured.get("/first").getSessionId())
                .isEqualTo(first.getSession().block().getId());
        assertThat(captured.get("/second").getSessionId())
                .isEqualTo(second.getSession().block().getId());
        assertThat(captured.get("/first").getSessionId())
                .isNotEqualTo(captured.get("/second").getSessionId());
        assertThat(captured.values())
                .allSatisfy(context -> assertThat(context.getEnvironment()).isEqualTo("production"));
        assertThat(LangfuseContext.getSessionId()).isNull();
    }

    private static MockServerWebExchange exchangeWithSession(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path).build());
        exchange.getSession().block();
        return exchange;
    }
}
