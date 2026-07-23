package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void publicBuilderDefaultsAutomaticEmbeddingInstrumentationToMetadataOnly() {
        EmbeddingModel proxy = proxy(
                new StubSpringAiEmbeddingModel(),
                LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        EmbeddingResponse response = proxy.call(new EmbeddingRequest(
                List.of("confidential embedding input"),
                new StubEmbeddingOptions("text-embedding-3-small")));

        assertThat(response.getResults()).hasSize(1);
        assertThat(otel.getSpans()).hasSize(1);

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("text-embedding-3-small");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")))
                .isEqualTo("text-embedding-3-small");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(5L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isNull();
    }

    @Test
    void publicBuilderMetadataOnlyRetainsEmbeddingErrorMetadata() {
        EmbeddingModel proxy = proxy(
                new ErrorSpringAiEmbeddingModel(),
                LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        EmbeddingRequest request = new EmbeddingRequest(
                List.of("confidential failure input"),
                new StubEmbeddingOptions("text-embedding-3-small"));

        assertThatThrownBy(() -> proxy.call(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("embedding error");

        SpanData span = otel.getSpans().get(0);
        assertThat(span.getStatus().getStatusCode().name()).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("text-embedding-3-small");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message")))
                .isEqualTo(RuntimeException.class.getName());
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().get(0).getAttributes()
                .get(AttributeKey.stringKey("exception.type"))).isEqualTo(RuntimeException.class.getName());
        assertThat(span.getEvents().get(0).getAttributes()
                .get(AttributeKey.stringKey("exception.message"))).isNull();
        assertThat(span.getEvents().get(0).getAttributes()
                .get(AttributeKey.stringKey("exception.stacktrace"))).isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isNull();
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

    @Test
    void documentEmbeddingIsDelegatedAndTraced() {
        EmbeddingModel proxy = proxy(new StubSpringAiEmbeddingModel());

        float[] embedding = proxy.embed(new Document("document embedding input"));

        assertThat(embedding).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isEqualTo("document embedding input");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("1 embedding (3 dimensions)");
    }

    @Test
    void documentEmbeddingRejectsNullDelegateResult() {
        EmbeddingModel proxy = proxy(new NullReturningSpringAiEmbeddingModel());

        assertThatThrownBy(() -> proxy.embed(new Document("document embedding input")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Spring AI EmbeddingModel delegate returned null from embed(Document)");

        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    @Test
    void bulkDocumentEmbeddingPreservesProviderOverrideAndIsTraced() {
        BulkOverrideSpringAiEmbeddingModel target = new BulkOverrideSpringAiEmbeddingModel();
        EmbeddingModel proxy = proxy(target);
        List<Document> documents = List.of(new Document("first"), new Document("second"));
        EmbeddingOptions options = new StubEmbeddingOptions("bulk-model");
        BatchingStrategy batchingStrategy = input -> List.of(input);

        List<float[]> embeddings = proxy.embed(documents, options, batchingStrategy);

        assertThat(target.bulkInvoked()).isTrue();
        assertThat(embeddings).hasSize(2);
        assertThat(embeddings.get(0)).containsExactly(1.0f);
        assertThat(embeddings.get(1)).containsExactly(2.0f);
        assertThat(otel.getSpans()).hasSize(1);
        SpanData span = otel.getSpans().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("bulk-model");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")))
                .isEqualTo("[first, second]");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("2 embedding(s)");
    }

    @Test
    void bulkDocumentEmbeddingRejectsNullDelegateResult() {
        EmbeddingModel proxy = proxy(new NullReturningSpringAiEmbeddingModel());
        List<Document> documents = List.of(new Document("first"), new Document("second"));
        BatchingStrategy batchingStrategy = input -> List.of(input);

        assertThatThrownBy(() -> proxy.embed(documents, null, batchingStrategy))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Spring AI EmbeddingModel delegate returned null from bulk embed");

        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }

    @Test
    void dimensionsDelegatesProviderOverrideWithoutCreatingAnEmbeddingSpan() {
        EmbeddingModel proxy = proxy(new StubSpringAiEmbeddingModel());

        int dimensions = proxy.dimensions();

        assertThat(dimensions).isEqualTo(1_536);
        assertThat(otel.getSpans()).isEmpty();
    }

    @Test
    void setupFailureAfterSpanCreationEndsSpanBeforeFallingBack() {
        EmbeddingModel proxy = proxy(new StubSpringAiEmbeddingModel());
        EmbeddingOptions throwingOptions = new EmbeddingOptions() {
            @Override
            public String getModel() {
                throw new IllegalStateException("options unavailable");
            }

            @Override
            public Integer getDimensions() {
                return null;
            }
        };

        EmbeddingResponse response = proxy.call(new EmbeddingRequest(
                List.of("fallback input"), throwingOptions));

        assertThat(response.getResults()).hasSize(1);
        assertThat(otel.getSpans()).hasSize(1);
    }

    private EmbeddingModel proxy(EmbeddingModel target) {
        return proxy(target, new LangfuseOtel(null, otel.getOpenTelemetry(), null, true));
    }

    private EmbeddingModel proxy(EmbeddingModel target, LangfuseOtel langfuseOtel) {
        return new io.github.chomingi.langfuse.otel.spring.TracingSpringAiEmbeddingModel(
                target,
                langfuseOtel);
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

        @Override
        public int dimensions() {
            return 1_536;
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

    static class BulkOverrideSpringAiEmbeddingModel extends StubSpringAiEmbeddingModel {
        private final AtomicBoolean bulkInvoked = new AtomicBoolean();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new AssertionError("bulk embedding unexpectedly used the default call path");
        }

        @Override
        public List<float[]> embed(List<Document> documents, EmbeddingOptions options,
                                   BatchingStrategy batchingStrategy) {
            bulkInvoked.set(true);
            return List.of(new float[]{1.0f}, new float[]{2.0f});
        }

        boolean bulkInvoked() {
            return bulkInvoked.get();
        }
    }

    static class NullReturningSpringAiEmbeddingModel extends StubSpringAiEmbeddingModel {
        @Override
        public float[] embed(Document document) {
            return null;
        }

        @Override
        public List<float[]> embed(List<Document> documents, EmbeddingOptions options,
                                   BatchingStrategy batchingStrategy) {
            return null;
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
