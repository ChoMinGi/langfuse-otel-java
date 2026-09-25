package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase-0 characterization: arbitrary raw manual generations are not deduplicated. */
class CorePreflightMixingTest {
    @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test void manualChainAndAutomaticGenerationHaveOneUsageTotal() { runAutomatic("chain", 12L); }
    @Test void manualGenerationAndAutomaticGenerationDoubleCountTheSameCall() { runAutomatic("generation", 24L); }

    private void runAutomatic(String type, long expectedTotal) {
        try (LangfuseOtel lf = LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build()) {
            ChatModel model = (ChatModel) ModelBeanPostProcessorSupport.instrumentSpringAi(new Model(), lf);
            Span manual = lf.getTracer().spanBuilder("manual").setAttribute("langfuse.observation.type", type).startSpan();
            try (Scope scope = manual.makeCurrent()) {
                model.call(new Prompt("synthetic"));
                if (type.equals("generation")) manual.setAttribute("gen_ai.usage.total_tokens", 12L);
            } finally { manual.end(); }
            assertThat(otel.getSpans()).hasSize(2);
            assertThat(otel.getSpans().get(0).getParentSpanId()).isEqualTo(manual.getSpanContext().getSpanId());
            assertThat(totalTokens()).isEqualTo(expectedTotal);
        }
    }

    @Test void disabledStarterWithExplicitCoreBeanCreatesNoModelProxyOrAnnotationAdvice() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                LangfuseOtelCoreAutoConfiguration.class, SpringAiAutoConfiguration.class,
                LangChain4jAutoConfiguration.class))
                .withPropertyValues("langfuse.enabled=false")
                .withBean(LangfuseOtel.class, () -> LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build())
                .withBean(Model.class, Model::new).run(context -> {
                    assertThat(context).doesNotHaveBean("springAiChatModelBeanPostProcessor");
                    assertThat(context).doesNotHaveBean("observeGenerationAspect");
                    Model model = context.getBean(Model.class);
                    assertThat(model.getClass()).isEqualTo(Model.class);
                    Span manual = context.getBean(LangfuseOtel.class).getTracer().spanBuilder("manual")
                            .setAttribute("langfuse.observation.type", "generation").startSpan();
                    try (Scope scope = manual.makeCurrent()) {
                        model.call(new Prompt("synthetic"));
                        manual.setAttribute("gen_ai.usage.total_tokens", 12L);
                    } finally { manual.end(); }
                    assertThat(otel.getSpans()).hasSize(1);
                    assertThat(totalTokens()).isEqualTo(12L);
                });
    }
    private long totalTokens() {
        return otel.getSpans().stream().map(s -> s.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens")))
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }
    public static class Model implements ChatModel {
        public ChatResponse call(Prompt prompt) {
            Usage usage = new Usage() {
                public Integer getPromptTokens() { return 5; }
                public Integer getCompletionTokens() { return 7; }
                public Object getNativeUsage() { return null; }
            };
            return new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic response"))),
                    ChatResponseMetadata.builder().model("preflight-model").usage(usage).build());
        }
    }
}
