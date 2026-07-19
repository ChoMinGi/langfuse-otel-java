package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseContextFilterTest {

    @Test
    void filterExtractsEnvironmentUserAndSessionAndClearsAfterwards() throws ServletException, IOException {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();
        properties.setEnvironment("staging");
        properties.getContext().setCaptureUserId(true);
        properties.getContext().setCaptureSessionId(true);

        LangfuseContextFilter filter = new LangfuseContextFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new Principal() {
            @Override
            public String getName() {
                return "demo-user";
            }
        });
        request.getSession(true);

        FilterChain chain = (req, res) -> {
            assertThat(LangfuseContext.getEnvironment()).isEqualTo("staging");
            assertThat(LangfuseContext.getUserId()).isEqualTo("demo-user");
            assertThat(LangfuseContext.getSessionId()).isEqualTo(request.getSession(false).getId());
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(LangfuseContext.getEnvironment()).isNull();
        assertThat(LangfuseContext.getUserId()).isNull();
        assertThat(LangfuseContext.getSessionId()).isNull();
    }

    @Test
    void principalAndHttpSessionAreNotCapturedByDefault() throws ServletException, IOException {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();
        LangfuseContextFilter filter = new LangfuseContextFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "sensitive-user");
        request.getSession(true);

        FilterChain chain = (req, res) -> {
            assertThat(LangfuseContext.getUserId()).isNull();
            assertThat(LangfuseContext.getSessionId()).isNull();
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }
}
