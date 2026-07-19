package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;

@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class LangfuseContextFilter extends OncePerRequestFilter {

    private final LangfuseOtelProperties properties;

    public LangfuseContextFilter(LangfuseOtelProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        LangfuseTraceContext.Builder contextBuilder = LangfuseTraceContext.builder()
                .environment(properties.getEnvironment());
        if (properties.getContext().isCaptureUserId()) {
            Principal principal = request.getUserPrincipal();
            if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
                contextBuilder.userId(principal.getName());
            }
        }
        if (properties.getContext().isCaptureSessionId()) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                contextBuilder.sessionId(session.getId());
            }
        }

        try (Scope ignored = LangfuseContext.makeCurrent(contextBuilder.build())) {
            filterChain.doFilter(request, response);
        }
    }
}
