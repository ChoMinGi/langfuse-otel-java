package io.github.chomingi.langfuse.otel;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingLangChain4jEmbeddingModelTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void embeddingCapturesRequestAndResponseAttributes() {
        EmbeddingModel proxy = proxy(new StubLangChain4jEmbeddingModel());

        Response<List<Embedding>> response = proxy.embedAll(
                List.of(TextSegment.from("What is Langfuse?")));

        assertThat(response.content()).hasSize(1);
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("embeddings");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isEqualTo("What is Langfuse?");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("1 embedding(s)");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(5L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(5L);
    }

    @Test
    void embeddingRecordsException() {
        EmbeddingModel proxy = proxy(new ErrorLangChain4jEmbeddingModel());

        assertThatThrownBy(() -> proxy.embedAll(List.of(TextSegment.from("fail"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("embedding error");

        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    @Test
    void multipleSegmentsAreSerialized() {
        EmbeddingModel proxy = proxy(new StubLangChain4jEmbeddingModel());

        proxy.embedAll(List.of(
                TextSegment.from("first"),
                TextSegment.from("second")));

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .contains("first").contains("second");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("2 embedding(s)");
    }

    @Test
    void singleStringEmbedConvenience() {
        EmbeddingModel proxy = proxy(new StubLangChain4jEmbeddingModel());

        Response<Embedding> response = proxy.embed("hello world");

        assertThat(response.content()).isNotNull();
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("embeddings");
    }

    private EmbeddingModel proxy(EmbeddingModel target) {
        return new io.github.chomingi.langfuse.otel.spring.TracingLangChain4jEmbeddingModel(
                target,
                new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    static class StubLangChain4jEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = segments.stream()
                    .map(s -> Embedding.from(new float[]{0.1f, 0.2f, 0.3f}))
                    .toList();
            return Response.from(embeddings, new TokenUsage(5, 0, 5));
        }
    }

    static class ErrorLangChain4jEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            throw new RuntimeException("embedding error");
        }
    }
}
