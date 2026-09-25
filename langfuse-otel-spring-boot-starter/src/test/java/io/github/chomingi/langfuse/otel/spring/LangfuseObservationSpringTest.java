package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class LangfuseObservationSpringTest {
    @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                LangfuseOtelCoreAutoConfiguration.class, SpringAiAutoConfiguration.class))
                .withBean(OpenTelemetry.class, otel::getOpenTelemetry, definition -> definition.setDestroyMethodName(""))
                .withBean(CorePreflightMixingTest.Model.class, CorePreflightMixingTest.Model::new);
    }

    @Test void starterBeanSupportsManualChainWithOneAutomaticGeneration() {
        runner().run(context -> {
            LangfuseOtel lf = context.getBean(LangfuseOtel.class);
            try (LangfuseObservation root = lf.observation("workflow", ObservationType.CHAIN)
                    .traceContext(LangfuseTraceContext.builder().userId("snapshot-user").build()).start();
                 Scope scope = root.makeCurrent()) {
                root.input("private");
                context.getBean(CorePreflightMixingTest.Model.class).call(new Prompt("private"));
            }
            assertThat(otel.getSpans()).hasSize(2);
            var generation = otel.getSpans().get(0);
            var root = otel.getSpans().get(1);
            assertThat(generation.getParentSpanId()).isEqualTo(root.getSpanId());
            assertThat(generation.getAttributes().get(AttributeKey.stringKey("user.id"))).isEqualTo("snapshot-user");
            assertThat(generation.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(12L);
            assertThat(root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.type"))).isEqualTo("chain");
            assertThat(root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isNull();
        });
    }

    @Test void starterContentPropertiesApplyToExplicitObservations() {
        runner().withPropertyValues("langfuse.content.capture-input=true", "langfuse.content.capture-output=false")
                .withBean(ContentRedactor.class, () -> (direction, content) -> "masked")
                .run(context -> {
                    try (LangfuseObservation observation = context.getBean(LangfuseOtel.class)
                            .observation("manual", ObservationType.TOOL).start()) {
                        observation.input("private").output("private");
                    }
                    assertThat(otel.getSpans()).hasSize(1);
                    assertThat(otel.getSpans().get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isEqualTo("masked");
                    assertThat(otel.getSpans().get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isNull();
                });
    }
}
