package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.security.Principal;

@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class LangfuseReactiveContextFilter implements WebFilter {

    private final LangfuseOtelProperties properties;

    public LangfuseReactiveContextFilter(LangfuseOtelProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Mono<Principal> principalMono = properties.getContext().isCaptureUserId()
                ? exchange.getPrincipal().defaultIfEmpty(new AnonymousPrincipal())
                : Mono.just(new AnonymousPrincipal());
        Mono<String> sessionIdMono = properties.getContext().isCaptureSessionId()
                ? exchange.getSession()
                        .map(session -> session.getId() != null ? session.getId() : "")
                        .defaultIfEmpty("")
                : Mono.just("");

        return Mono.zip(principalMono, sessionIdMono)
                .flatMap(tuple -> {
                    LangfuseTraceContext.Builder contextBuilder = LangfuseTraceContext.builder()
                            .environment(properties.getEnvironment());
                    Principal principal = tuple.getT1();
                    if (!(principal instanceof AnonymousPrincipal)
                            && principal.getName() != null && !principal.getName().isBlank()) {
                        contextBuilder.userId(principal.getName());
                    }
                    String sessionId = tuple.getT2();
                    if (!sessionId.isEmpty()) {
                        contextBuilder.sessionId(sessionId);
                    }
                    LangfuseTraceContext traceContext = contextBuilder.build();

                    return chain.filter(exchange)
                            .contextWrite(context -> context.put(LangfuseContext.reactorContextKey(), traceContext));
                });
    }

    private static class AnonymousPrincipal implements Principal {
        @Override
        public String getName() {
            return null;
        }
    }
}
