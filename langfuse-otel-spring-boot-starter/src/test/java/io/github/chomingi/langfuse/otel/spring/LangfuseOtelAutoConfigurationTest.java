package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGenerationAspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LangfuseOtelAutoConfiguration.class,
                    SpringAiAutoConfiguration.class,
                    LangChain4jAutoConfiguration.class))
            .withPropertyValues(
                    "langfuse.public-key=pk-test",
                    "langfuse.secret-key=sk-test",
                    "langfuse.service-name=test-service");

    @Test
    void starterRegistersCoreAndAspectBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LangfuseOtel.class);
            assertThat(context).hasSingleBean(ObserveGenerationAspect.class);
            assertThat(context).hasSingleBean(SpringAiChatModelBeanPostProcessor.class);
            assertThat(context).hasSingleBean(LangChain4jChatModelBeanPostProcessor.class);
            assertThat(context).hasSingleBean(LangfuseContextFilter.class);
        });
    }

    @Test
    void starterCanBeDisabled() {
        contextRunner.withPropertyValues("langfuse.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LangfuseOtel.class);
                    assertThat(context).doesNotHaveBean(ObserveGenerationAspect.class);
                });
    }
}
