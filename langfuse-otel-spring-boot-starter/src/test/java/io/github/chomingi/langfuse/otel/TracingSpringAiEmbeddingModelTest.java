package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingSpringAiEmbeddingModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void embeddingCapturesRequestAndResponseAttributes() {
        EmbeddingModel proxy = proxy(new StubSpringAiEmbeddingModel());

        EmbeddingRequest request = new EmbeddingRequest(
                List.of("What is Langfuse?"),
                new StubEmbeddingOptions("text-embedding-3-small"));

        EmbeddingResponse response = proxy.call(request);

        assertThat(response.getResults()).hasSize(1);
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("embeddings");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("spring-ai");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("text-embedding-3-small");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isEqualTo("What is Langfuse?");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("1 embedding(s)");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(5L);
    }

    @Test
    void embeddingRecordsException() {
        EmbeddingModel proxy = proxy(new ErrorSpringAiEmbeddingModel());

        EmbeddingRequest request = new EmbeddingRequest(List.of("fail"), null);

        assertThatThrownBy(() -> proxy.call(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("embedding error");

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
    }

    @Test
    void multipleInputsAreSerialized() {
        EmbeddingModel proxy = proxy(new StubSpringAiEmbeddingModel());

        EmbeddingRequest request = new EmbeddingRequest(
                List.of("first", "second", "third"), null);

        proxy.call(request);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("first").contains("second").contains("third");
    }

    private EmbeddingModel proxy(EmbeddingModel target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingSpringAiEmbeddingModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    static class StubSpringAiEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            StubUsage usage = new StubUsage(5, 0);
            EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata(
                    "text-embedding-3-small", usage);
            return new EmbeddingResponse(
                    List.of(new Embedding(new float[]{0.1f, 0.2f, 0.3f}, 0)),
                    metadata);
        }

        @Override
        public float[] embed(Document document) {
            return new float[]{0.1f, 0.2f, 0.3f};
        }
    }

    static class ErrorSpringAiEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new RuntimeException("embedding error");
        }

        @Override
        public float[] embed(Document document) {
            throw new RuntimeException("embedding error");
        }
    }

    static class StubUsage implements Usage {
        private final Integer promptTokens;
        private final Integer completionTokens;

        StubUsage(Integer promptTokens, Integer completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override public Integer getPromptTokens() { return promptTokens; }
        @Override public Integer getCompletionTokens() { return completionTokens; }
        @Override public Object getNativeUsage() { return null; }
    }

    static class StubEmbeddingOptions implements EmbeddingOptions {
        private final String model;

        StubEmbeddingOptions(String model) { this.model = model; }

        @Override public String getModel() { return model; }
        @Override public Integer getDimensions() { return null; }
    }
}
