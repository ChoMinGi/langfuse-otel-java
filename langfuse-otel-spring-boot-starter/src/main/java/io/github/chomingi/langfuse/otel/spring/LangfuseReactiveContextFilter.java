package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.security.Principal;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LangfuseReactiveContextFilter implements WebFilter {

    private final LangfuseOtelProperties properties;

    public LangfuseReactiveContextFilter(LangfuseOtelProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Mono<Principal> principalMono = exchange.getPrincipal()
                .defaultIfEmpty(new AnonymousPrincipal());
        Mono<String> sessionIdMono = exchange.getSession()
                .map(session -> session.getId() != null ? session.getId() : "")
                .defaultIfEmpty("");

        return Mono.zip(principalMono, sessionIdMono)
                .flatMap(tuple -> {
                    try {
                        if (properties.getEnvironment() != null) {
                            LangfuseContext.setEnvironment(properties.getEnvironment());
                        }
                        Principal principal = tuple.getT1();
                        if (!(principal instanceof AnonymousPrincipal)
                                && principal.getName() != null && !principal.getName().isBlank()) {
                            LangfuseContext.setUserId(principal.getName());
                        }
                        String sessionId = tuple.getT2();
                        if (!sessionId.isEmpty()) {
                            LangfuseContext.setSessionId(sessionId);
                        }
                    } catch (Exception ignored) {
                    }

                    return chain.filter(exchange)
                            .doFinally(signalType -> LangfuseContext.clear());
                });
    }

    private static class AnonymousPrincipal implements Principal {
        @Override
        public String getName() {
            return null;
        }
    }
}
