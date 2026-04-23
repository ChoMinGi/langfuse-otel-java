package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseAttributeVerificationTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void generation_setsCorrectAttributes() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "test-gen")) {
            gen.model("gpt-4o")
               .system("openai")
               .temperature(0.7)
               .maxTokens(1024)
               .input("hello")
               .output("world")
               .inputTokens(10)
               .outputTokens(20)
               .totalTokens(30)
               .promptName("my-prompt")
               .promptVersion(3);
        }

        List<SpanData> spans = otel.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("test-gen");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("chat");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system"))).isEqualTo("openai");
        assertThat(span.getAttributes().get(AttributeKey.doubleKey("gen_ai.request.temperature"))).isEqualTo(0.7);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.request.max_tokens"))).isEqualTo(1024L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isEqualTo("hello");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("world");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(10L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(20L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens"))).isEqualTo(30L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.prompt.name"))).isEqualTo("my-prompt");
        assertThat(span.getAttributes().get(AttributeKey.longKey("langfuse.observation.prompt.version"))).isEqualTo(3L);
    }

    @Test
    void generation_operationNameOverride() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "embed")) {
            gen.operationName("embeddings").model("text-embedding-3-small");
        }

        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("embeddings");
    }

    @Test
    void trace_setsCorrectAttributes() {
        try (LangfuseTrace trace = new LangfuseTrace(otel.getOpenTelemetry().getTracer("test"), "test-trace")) {
            trace.userId("user-1")
                 .sessionId("sess-1")
                 .tags("prod", "v2")
                 .input("trace-input")
                 .output("trace-output")
                 .metadata("custom", "value");
        }

        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getName()).isEqualTo("test-trace");
        assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.trace.name"))).isEqualTo("test-trace");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("user.id"))).isEqualTo("user-1");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("session.id"))).isEqualTo("sess-1");
        assertThat(span.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags"))).containsExactly("prod", "v2");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.trace.input"))).isEqualTo("trace-input");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.trace.output"))).isEqualTo("trace-output");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.trace.metadata.custom"))).isEqualTo("value");
    }

    @Test
    void span_setsCorrectAttributes() {
        try (LangfuseSpan span = new LangfuseSpan(otel.getOpenTelemetry().getTracer("test"), "test-span")) {
            span.input("span-input").output("span-output");
        }

        SpanData spanData = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(spanData.getName()).isEqualTo("test-span");
        assertThat(spanData.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isEqualTo("span-input");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).isEqualTo("span-output");
    }

    @Test
    void errorCapture_setsStatusAndAttributes() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "error-gen")) {
            gen.recordException(new RuntimeException("test error"));
        }

        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("test error");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message"))).isEqualTo("test error");
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().get(0).getName()).isEqualTo("exception");
    }

    @Test
    void errorCapture_handlesNullMessage() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "null-msg-gen")) {
            gen.recordException(new NullPointerException());
        }

        SpanData span = otel.getSpans().get(otel.getSpans().size() - 1);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message"))).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void parentChildRelationship() {
        try (LangfuseTrace trace = new LangfuseTrace(otel.getOpenTelemetry().getTracer("test"), "parent")) {
            try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "child")) {
                gen.model("gpt-4o");
            }
        }

        List<SpanData> spans = otel.getSpans();
        SpanData child = spans.stream().filter(s -> s.getName().equals("child")).findFirst().orElseThrow();
        SpanData parent = spans.stream().filter(s -> s.getName().equals("parent")).findFirst().orElseThrow();

        assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    }
}
