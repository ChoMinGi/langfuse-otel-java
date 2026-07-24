package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseOtelStatus;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelAutoConfigurationCompatibilityTest {

    @Test
    @SuppressWarnings("deprecation")
    void legacyFactoryOverloadUsesStandaloneDefaultsWithoutSpringBeanLookup() {
        LangfuseOtelProperties properties = new LangfuseOtelProperties();
        properties.getContent().setCaptureInput(true);
        properties.getException().setCaptureMessage(true);

        try (LangfuseOtel langfuse = new LangfuseOtelAutoConfiguration().langfuseOtel(properties)) {
            assertThat(langfuse.isNoop()).isTrue();
            assertThat(langfuse.getStatus().getNoopReason())
                    .isEqualTo(LangfuseOtelStatus.NoopReason.MISSING_CREDENTIALS);
            assertThat(langfuse.getContentCapturePolicy().isInputCaptureEnabled()).isTrue();
            assertThat(langfuse.getExceptionCapturePolicy().isMessageCaptureEnabled()).isTrue();
        }
    }

    @Test
    void legacyFacadePreservesFactoriesWithoutRegisteringBeanMethods() throws NoSuchMethodException {
        Method legacyFactory = LangfuseOtelAutoConfiguration.class
                .getMethod("langfuseOtel", LangfuseOtelProperties.class);
        Method servletFactory = LangfuseOtelAutoConfiguration.class
                .getMethod("langfuseContextFilter", LangfuseOtelProperties.class);
        Method reactiveFactory = LangfuseOtelAutoConfiguration.class
                .getMethod("langfuseReactiveContextFilter", LangfuseOtelProperties.class);

        assertThat(legacyFactory.getReturnType()).isEqualTo(LangfuseOtel.class);
        assertThat(legacyFactory.isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(servletFactory.getReturnType()).isEqualTo(LangfuseContextFilter.class);
        assertThat(reactiveFactory.getReturnType()).isEqualTo(LangfuseReactiveContextFilter.class);
        assertThat(Arrays.stream(LangfuseOtelAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class)))
                .isEmpty();
        assertThat(Arrays.stream(LangfuseOtelCoreAutoConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("langfuseOtel"))
                .filter(method -> method.isAnnotationPresent(Bean.class)))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterCount()).isEqualTo(4));
    }
}
