package io.github.chomingi.langfuse.otel.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelWebApplicationAutoConfigurationTest {

    private final WebApplicationContextRunner servletContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LangfuseOtelAutoConfiguration.class))
            .withPropertyValues(
                    "langfuse.public-key=pk-test",
                    "langfuse.secret-key=sk-test");

    private final ReactiveWebApplicationContextRunner reactiveContextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(LangfuseOtelAutoConfiguration.class))
                    .withPropertyValues(
                            "langfuse.public-key=pk-test",
                            "langfuse.secret-key=sk-test");

    @Test
    void mixedClasspathServletApplicationRegistersOnlyServletFilter() {
        servletContextRunner.run(context -> {
            assertThat(context).hasSingleBean(LangfuseContextFilter.class);
            assertThat(context).doesNotHaveBean(LangfuseReactiveContextFilter.class);
        });
    }

    @Test
    void mixedClasspathReactiveApplicationRegistersOnlyReactiveFilter() {
        reactiveContextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(LangfuseContextFilter.class);
            assertThat(context).hasSingleBean(LangfuseReactiveContextFilter.class);
        });
    }
}
