package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LangfuseContextFilter extends OncePerRequestFilter {

    private final LangfuseOtelProperties properties;

    public LangfuseContextFilter(LangfuseOtelProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (properties.getEnvironment() != null) {
                LangfuseContext.setEnvironment(properties.getEnvironment());
            }

            filterChain.doFilter(request, response);
        } finally {
            LangfuseContext.clear();
        }
    }
}
