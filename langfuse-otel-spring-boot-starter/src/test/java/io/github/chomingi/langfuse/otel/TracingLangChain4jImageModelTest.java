package io.github.chomingi.langfuse.otel;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingLangChain4jImageModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void imageCapturesRequestAndResponseAttributes() {
        ImageModel proxy = proxy(new StubLangChain4jImageModel());

        Response<Image> response = proxy.generate("A cute cat");

        assertThat(response.content()).isNotNull();
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("image_generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isEqualTo("A cute cat");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("1 image generated");
    }

    @Test
    void multipleImagesGenerated() {
        ImageModel proxy = proxy(new StubLangChain4jImageModel());

        Response<List<Image>> response = proxy.generate("A cute cat", 3);

        assertThat(response.content()).hasSize(3);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("3 image(s) generated");
    }

    @Test
    void imageRecordsException() {
        ImageModel proxy = proxy(new ErrorLangChain4jImageModel());

        assertThatThrownBy(() -> proxy.generate("fail"))
                .isInstanceOf(RuntimeException.class);

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    private ImageModel proxy(ImageModel target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingLangChain4jImageModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    static class StubLangChain4jImageModel implements ImageModel {
        @Override
        public Response<Image> generate(String prompt) {
            Image image = Image.builder().url(URI.create("https://example.com/cat.png")).build();
            return Response.from(image);
        }

        @Override
        public Response<List<Image>> generate(String prompt, int n) {
            List<Image> images = java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> Image.builder().url(URI.create("https://example.com/img" + i + ".png")).build())
                    .toList();
            return Response.from(images);
        }
    }

    static class ErrorLangChain4jImageModel implements ImageModel {
        @Override
        public Response<Image> generate(String prompt) {
            throw new RuntimeException("image generation error");
        }
    }
}
