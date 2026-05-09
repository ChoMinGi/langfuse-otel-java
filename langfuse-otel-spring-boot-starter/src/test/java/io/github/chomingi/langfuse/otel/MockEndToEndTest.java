package io.github.chomingi.langfuse.otel;

import io.github.chomingi.langfuse.otel.spring.*;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.data.segment.TextSegment;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MockEndToEndTest {

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;
    private LangfuseOtel langfuseOtel;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetrySdk otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        langfuseOtel = new LangfuseOtel(tracerProvider, otelSdk, null, false);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    // === Auto-Configuration bean registration ===

    @Test
    void allBeanPostProcessorsAreRegistered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LangfuseOtelAutoConfiguration.class,
                        SpringAiAutoConfiguration.class,
                        LangChain4jAutoConfiguration.class))
                .withPropertyValues(
                        "langfuse.public-key=pk-test",
                        "langfuse.secret-key=sk-test")
                .run(context -> {
                    // Core
                    assertThat(context).hasSingleBean(LangfuseOtel.class);
                    // Spring AI — check by bean name (BPP classes are package-private)
                    assertThat(context).hasBean("springAiChatModelBeanPostProcessor");
                    assertThat(context).hasBean("springAiEmbeddingModelBeanPostProcessor");
                    assertThat(context).hasBean("springAiImageModelBeanPostProcessor");
                    // LangChain4j
                    assertThat(context).hasBean("langChain4jChatModelBeanPostProcessor");
                    assertThat(context).hasBean("langChain4jEmbeddingModelBeanPostProcessor");
                    assertThat(context).hasBean("langChain4jImageModelBeanPostProcessor");
                });
    }

    // === Spring AI — full flow ===

    @Test
    void springAiSyncChatProducesCorrectSpan() {
        ChatModel model = new TracingSpringAiChatModel(new StubSpringAiChatModel(), langfuseOtel);

        org.springframework.ai.chat.model.ChatResponse response = model.call(
                new Prompt("What is OTel?", ChatOptions.builder().model("gpt-4o").build()));

        assertThat(response.getResult().getOutput().getText()).contains("answer");
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertSpanAttributes(spans.get(0), "chat", "spring-ai", "gpt-4o");
    }

    @Test
    void springAiStreamProducesCorrectSpan() {
        ChatModel model = new TracingSpringAiChatModel(new StubSpringAiChatModel(), langfuseOtel);

        List<org.springframework.ai.chat.model.ChatResponse> chunks =
                model.stream(new Prompt("Stream test")).collectList().block();

        assertThat(chunks).hasSize(2);
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("chat");
        assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("Hello World");
    }

    @Test
    void springAiEmbeddingProducesCorrectSpan() {
        EmbeddingModel model = new TracingSpringAiEmbeddingModel(new StubSpringAiEmbeddingModel(), langfuseOtel);

        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("test input"), null));

        assertThat(response.getResults()).hasSize(1);
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertSpanAttributes(spans.get(0), "embeddings", "spring-ai", null);
    }

    @Test
    void springAiImageProducesCorrectSpan() {
        ImageModel model = new TracingSpringAiImageModel(new StubSpringAiImageModel(), langfuseOtel);

        ImageResponse response = model.call(new ImagePrompt("A cat"));

        assertThat(response.getResults()).hasSize(1);
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertSpanAttributes(spans.get(0), "image_generation", "spring-ai", null);
    }

    // === LangChain4j — full flow ===

    @Test
    void langChain4jSyncChatProducesCorrectSpan() {
        TracingLangChain4jChatModel model = new TracingLangChain4jChatModel(
                new StubLangChain4jChatModel(), langfuseOtel);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from("hello"))
                .parameters(DefaultChatRequestParameters.builder().modelName("gpt-4o").build())
                .build());

        assertThat(response.aiMessage().text()).contains("answer");
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertSpanAttributes(spans.get(0), "chat", "langchain4j", "gpt-4o");
    }

    @Test
    void langChain4jStreamingChatProducesCorrectSpan() throws Exception {
        TracingStreamingLangChain4jChatModel model = new TracingStreamingLangChain4jChatModel(
                new StubStreamingLangChain4jChatModel(), langfuseOtel);

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder accumulated = new StringBuilder();

        ((StreamingChatModel) model).chat(ChatRequest.builder()
                .messages(UserMessage.from("stream test"))
                .build(), new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String partial) { accumulated.append(partial); }
            @Override public void onCompleteResponse(ChatResponse r) { future.complete(r); }
            @Override public void onError(Throwable error) { future.completeExceptionally(error); }
        });

        future.get(5, TimeUnit.SECONDS);
        assertThat(accumulated.toString()).isEqualTo("AB");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("chat");
    }

    @Test
    void langChain4jEmbeddingProducesCorrectSpan() {
        TracingLangChain4jEmbeddingModel model = new TracingLangChain4jEmbeddingModel(
                new StubLangChain4jEmbeddingModel(), langfuseOtel);

        Response<List<Embedding>> response = model.embedAll(List.of(TextSegment.from("test")));

        assertThat(response.content()).hasSize(1);
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertSpanAttributes(spans.get(0), "embeddings", "langchain4j", null);
    }

    @Test
    void langChain4jImageProducesCorrectSpan() {
        TracingLangChain4jImageModel model = new TracingLangChain4jImageModel(
                new StubLangChain4jImageModel(), langfuseOtel);

        Response<Image> response = model.generate("A dog");

        assertThat(response.content()).isNotNull();
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertSpanAttributes(spans.get(0), "image_generation", "langchain4j", null);
    }

    // === Multi-model flow ===

    @Test
    void multiModelTraceProducesMultipleSpans() {
        ChatModel chatModel = new TracingSpringAiChatModel(new StubSpringAiChatModel(), langfuseOtel);
        EmbeddingModel embModel = new TracingSpringAiEmbeddingModel(new StubSpringAiEmbeddingModel(), langfuseOtel);
        ImageModel imgModel = new TracingSpringAiImageModel(new StubSpringAiImageModel(), langfuseOtel);

        // Simulate a workflow: embed → chat → generate image
        embModel.call(new EmbeddingRequest(List.of("context text"), null));
        chatModel.call(new Prompt("Describe a cat"));
        imgModel.call(new ImagePrompt("A cat"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);

        List<String> operations = spans.stream()
                .map(s -> s.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                .toList();
        assertThat(operations).containsExactly("embeddings", "chat", "image_generation");
    }

    // === Helpers ===

    private void assertSpanAttributes(SpanData span, String operation, String system, String model) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo(operation);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo(system);
        if (model != null) {
            assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo(model);
        }
    }

    // === Stubs ===

    static class StubSpringAiChatModel implements ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            String input = prompt.getInstructions().isEmpty() ? "" : prompt.getInstructions().get(0).getText();
            StubUsage usage = new StubUsage(5, 7);
            ChatResponseMetadata metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage("answer: " + input))), metadata);
        }

        @Override
        public Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt prompt) {
            return Flux.just(
                    new org.springframework.ai.chat.model.ChatResponse(
                            List.of(new Generation(new AssistantMessage("Hello ")))),
                    new org.springframework.ai.chat.model.ChatResponse(
                            List.of(new Generation(new AssistantMessage("World")))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("gpt-4o").build();
        }
    }

    static class StubSpringAiEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(
                    List.of(new org.springframework.ai.embedding.Embedding(new float[]{0.1f}, 0)),
                    new EmbeddingResponseMetadata("text-embedding-3-small", new StubUsage(3, 0)));
        }

        @Override
        public float[] embed(Document document) {
            return new float[]{0.1f};
        }
    }

    static class StubSpringAiImageModel implements ImageModel {
        @Override
        public ImageResponse call(ImagePrompt prompt) {
            return new ImageResponse(List.of(
                    new ImageGeneration(new org.springframework.ai.image.Image("https://img.test/cat.png", null))));
        }
    }

    static class StubLangChain4jChatModel implements dev.langchain4j.model.chat.ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            List<ChatMessage> msgs = request.messages();
            ChatMessage last = msgs.get(msgs.size() - 1);
            String text = last instanceof UserMessage ? ((UserMessage) last).singleText() : last.toString();
            return ChatResponse.builder().modelName("gpt-4o")
                    .aiMessage(AiMessage.from("answer: " + text))
                    .tokenUsage(new TokenUsage(4, 5, 9)).build();
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("gpt-4o").build();
        }
    }

    static class StubStreamingLangChain4jChatModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("A");
            handler.onPartialResponse("B");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("AB")).tokenUsage(new TokenUsage(2, 2, 4)).build());
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("gpt-4o").build();
        }
    }

    static class StubLangChain4jEmbeddingModel implements dev.langchain4j.model.embedding.EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(List.of(Embedding.from(new float[]{0.1f})), new TokenUsage(3, 0, 3));
        }
    }

    static class StubLangChain4jImageModel implements dev.langchain4j.model.image.ImageModel {
        @Override
        public Response<Image> generate(String prompt) {
            return Response.from(Image.builder().url(URI.create("https://img.test/dog.png")).build());
        }
    }

    static class StubUsage implements Usage {
        private final Integer prompt, completion;
        StubUsage(Integer prompt, Integer completion) { this.prompt = prompt; this.completion = completion; }
        @Override public Integer getPromptTokens() { return prompt; }
        @Override public Integer getCompletionTokens() { return completion; }
        @Override public Object getNativeUsage() { return null; }
    }
}
