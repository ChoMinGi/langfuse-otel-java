package io.github.chomingi.langfuse.otel;

import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGenerationAspect;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObserveGenerationAspectTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void annotationCapturesConfiguredMetadataAndOutput() {
        TestService proxy = proxy(new TestService());

        String result = proxy.summarize("hello");

        assertThat(result).isEqualTo("summary: hello");
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getName()).isEqualTo("summarize");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("openai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("summary: hello");
    }

    @Test
    void annotationCapturesExceptions() {
        TestService proxy = proxy(new TestService());

        assertThatThrownBy(proxy::fail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message"))).isEqualTo("boom");
    }

    private TestService proxy(TestService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ObserveGenerationAspect(new LangfuseOtel(null, otel.getOpenTelemetry(), null, true)));
        return factory.getProxy();
    }

    static class TestService {

        @ObserveGeneration(name = "summarize", model = "gpt-4o-mini", system = "openai")
        String summarize(String text) {
            return "summary: " + text;
        }

        @ObserveGeneration(name = "explode", model = "gpt-4o-mini", system = "openai")
        String fail() {
            throw new IllegalStateException("boom");
        }
    }
}
