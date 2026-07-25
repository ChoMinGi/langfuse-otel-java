package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelWebApplicationAutoConfigurationTest {

    private final WebApplicationContextRunner servletContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LangfuseOtelCoreAutoConfiguration.class,
                    LangfuseOtelServletAutoConfiguration.class,
                    LangfuseOtelReactiveAutoConfiguration.class))
            .withPropertyValues(
                    "langfuse.public-key=pk-test",
                    "langfuse.secret-key=sk-test");

    private final ReactiveWebApplicationContextRunner reactiveContextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            LangfuseOtelCoreAutoConfiguration.class,
                            LangfuseOtelServletAutoConfiguration.class,
                            LangfuseOtelReactiveAutoConfiguration.class))
                    .withPropertyValues(
                            "langfuse.public-key=pk-test",
                            "langfuse.secret-key=sk-test");

    @Test
    void nonWebApplicationWithoutWebClassesStartsCoreOnly() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(
                        "jakarta.servlet",
                        "org.springframework.web.filter",
                        "org.springframework.web.server",
                        "org.springframework.web.reactive"))
                .withConfiguration(AutoConfigurations.of(
                        LangfuseOtelCoreAutoConfiguration.class,
                        LangfuseOtelServletAutoConfiguration.class,
                        LangfuseOtelReactiveAutoConfiguration.class))
                .withPropertyValues(
                        "langfuse.public-key=pk-test",
                        "langfuse.secret-key=sk-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    assertThat(context).doesNotHaveBean("langfuseContextFilter");
                    assertThat(context).doesNotHaveBean("langfuseReactiveContextFilter");
                });
    }

    @Test
    void mixedClasspathServletApplicationRegistersOnlyServletFilter() {
        servletContextRunner.run(context -> {
            assertThat(context.getBeanNamesForType(LangfuseContextFilter.class))
                    .containsExactly("langfuseContextFilter");
            assertThat(context).doesNotHaveBean("langfuseReactiveContextFilter");
        });
    }

    @Test
    void mixedClasspathReactiveApplicationRegistersOnlyReactiveFilter() {
        reactiveContextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("langfuseContextFilter");
            assertThat(context.getBeanNamesForType(LangfuseReactiveContextFilter.class))
                    .containsExactly("langfuseReactiveContextFilter");
        });
    }

    @Test
    void disabledIntegrationDoesNotRegisterWebFilters() {
        servletContextRunner.withPropertyValues("langfuse.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("langfuseContextFilter");
                    assertThat(context).doesNotHaveBean("langfuseReactiveContextFilter");
                });
        reactiveContextRunner.withPropertyValues("langfuse.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("langfuseContextFilter");
                    assertThat(context).doesNotHaveBean("langfuseReactiveContextFilter");
                });
    }

    @Test
    void autoConfigurationImportsContainTheSplitConfigurations() {
        assertThat(ImportCandidates.load(
                AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(
                        LangfuseOtelCoreAutoConfiguration.class.getName(),
                        LangfuseOtelServletAutoConfiguration.class.getName(),
                        LangfuseOtelReactiveAutoConfiguration.class.getName())
                .doesNotContain(LangfuseOtelAutoConfiguration.class.getName());
    }
}
