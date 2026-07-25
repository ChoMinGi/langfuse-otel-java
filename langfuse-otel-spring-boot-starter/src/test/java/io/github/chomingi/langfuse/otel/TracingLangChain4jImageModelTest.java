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
        assertThat(span.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_TYPE))).isEqualTo("generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("image_generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isEqualTo("A cute cat");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("1 image generated");
    }

    @Test
    void publicBuilderDefaultsAutomaticImageInstrumentationToMetadataOnly() {
        ImageModel proxy = proxy(
                new StubLangChain4jImageModel(),
                LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        Response<Image> response = proxy.generate("confidential image prompt");

        assertThat(response.content()).isNotNull();
        assertThat(response.content().url())
                .isEqualTo(URI.create("https://example.com/cat.png"));
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("image_generation");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")))
                .isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isNull();
    }

    @Test
    void multipleImagesGenerated() {
        ImageModel proxy = proxy(new StubLangChain4jImageModel());

        Response<List<Image>> response = proxy.generate("A cute cat", 3);

        assertThat(response.content()).hasSize(3);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_TYPE))).isEqualTo("generation");
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

    @Test
    void imageEditOverloadsRemainDelegatedAndTraced() {
        ImageModel proxy = proxy(new StubLangChain4jImageModel());
        Image source = Image.builder().url(URI.create("https://example.com/source.png")).build();
        Image mask = Image.builder().url(URI.create("https://example.com/mask.png")).build();

        Response<Image> edited = proxy.edit(source, "make it blue");
        Response<Image> masked = proxy.edit(source, mask, "replace the sky");

        assertThat(edited.content().url()).isEqualTo(URI.create("https://example.com/edited.png"));
        assertThat(masked.content().url()).isEqualTo(URI.create("https://example.com/masked.png"));
        assertThat(otel.getSpans()).hasSize(2);
        assertThat(otel.getSpans())
                .allSatisfy(span -> assertThat(span.getAttributes().get(
                        AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_TYPE)))
                        .isEqualTo("generation"));
        assertThat(otel.getSpans())
                .extracting(span -> span.getAttributes().get(
                        AttributeKey.stringKey("langfuse.observation.input")))
                .containsExactlyInAnyOrder("make it blue", "replace the sky");
    }

    private ImageModel proxy(ImageModel target) {
        return proxy(target, new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    private ImageModel proxy(ImageModel target, LangfuseOtel langfuseOtel) {
        return new io.github.chomingi.langfuse.otel.spring.TracingLangChain4jImageModel(
                target,
                langfuseOtel);
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

        @Override
        public Response<Image> edit(Image image, String prompt) {
            return Response.from(Image.builder()
                    .url(URI.create("https://example.com/edited.png")).build());
        }

        @Override
        public Response<Image> edit(Image image, Image mask, String prompt) {
            return Response.from(Image.builder()
                    .url(URI.create("https://example.com/masked.png")).build());
        }
    }

    static class ErrorLangChain4jImageModel implements ImageModel {
        @Override
        public Response<Image> generate(String prompt) {
            throw new RuntimeException("image generation error");
        }
    }
}
