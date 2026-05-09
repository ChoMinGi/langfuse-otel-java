package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingSpringAiImageModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void imageCapturesRequestAndResponseAttributes() {
        ImageModel proxy = proxy(new StubSpringAiImageModel());

        ImagePrompt prompt = new ImagePrompt("A cute cat",
                ImageOptionsBuilder.builder().model("dall-e-3").build());

        ImageResponse response = proxy.call(prompt);

        assertThat(response.getResults()).hasSize(1);
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("image_generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("spring-ai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("dall-e-3");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isEqualTo("A cute cat");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("1 image(s) generated");
    }

    @Test
    void imageRecordsException() {
        ImageModel proxy = proxy(new ErrorSpringAiImageModel());

        assertThatThrownBy(() -> proxy.call(new ImagePrompt("fail")))
                .isInstanceOf(RuntimeException.class);

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    private ImageModel proxy(ImageModel target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingSpringAiImageModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    static class StubSpringAiImageModel implements ImageModel {
        @Override
        public ImageResponse call(ImagePrompt prompt) {
            return new ImageResponse(List.of(
                    new ImageGeneration(new Image("https://example.com/cat.png", null))));
        }
    }

    static class ErrorSpringAiImageModel implements ImageModel {
        @Override
        public ImageResponse call(ImagePrompt prompt) {
            throw new RuntimeException("image generation error");
        }
    }
}
