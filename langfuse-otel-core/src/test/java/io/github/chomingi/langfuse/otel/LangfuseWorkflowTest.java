package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseWorkflowTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void embeddingsOperation() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "embed-call")) {
            gen.operationName("embeddings")
               .model("text-embedding-3-small")
               .system("openai")
               .input("인사평가 기준을 요약해줘")
               .inputTokens(12);
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("embeddings");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("text-embedding-3-small");
    }

    @Test
    void textCompletionOperation() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "completion")) {
            gen.operationName("text_completion")
               .model("gpt-3.5-turbo-instruct")
               .input("Complete this: Java is")
               .output("a programming language");
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("text_completion");
    }

    @Test
    void multipleGenerationsInOneTrace() {
        var tracer = otel.getOpenTelemetry().getTracer("test");

        try (LangfuseTrace trace = new LangfuseTrace(tracer, "multi-gen-flow")) {
            trace.userId("user-1");

            try (LangfuseGeneration gen1 = new LangfuseGeneration(tracer, "first-llm")) {
                gen1.model("gpt-4o").input("질문 1").output("답변 1").inputTokens(10).outputTokens(20);
            }

            try (LangfuseGeneration gen2 = new LangfuseGeneration(tracer, "second-llm")) {
                gen2.model("claude-sonnet-4-20250514").system("anthropic").input("질문 2").output("답변 2");
            }
        }

        List<SpanData> spans = otel.getSpans();
        List<String> names = spans.stream().map(SpanData::getName).collect(Collectors.toList());
        assertThat(names).contains("multi-gen-flow", "first-llm", "second-llm");

        SpanData gen1 = spans.stream().filter(s -> s.getName().equals("first-llm")).findFirst().orElseThrow();
        SpanData gen2 = spans.stream().filter(s -> s.getName().equals("second-llm")).findFirst().orElseThrow();
        SpanData trace = spans.stream().filter(s -> s.getName().equals("multi-gen-flow")).findFirst().orElseThrow();

        assertThat(gen1.getParentSpanId()).isEqualTo(trace.getSpanContext().getSpanId());
        assertThat(gen2.getParentSpanId()).isEqualTo(trace.getSpanContext().getSpanId());
        assertThat(gen1.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o");
        assertThat(gen2.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("claude-sonnet-4-20250514");
    }

    @Test
    void callbackApi_errorPropagatesAndRecords() {
        var tracer = otel.getOpenTelemetry().getTracer("test");
        LangfuseOtel langfuse = new LangfuseOtel(null, otel.getOpenTelemetry(), null, true);

        assertThatThrownBy(() -> {
            langfuse.trace("error-flow", trace -> {
                trace.generation("failing-gen", gen -> {
                    gen.model("gpt-4o");
                    throw new RuntimeException("API timeout");
                });
            });
        }).isInstanceOf(RuntimeException.class)
          .hasMessage("API timeout");

        SpanData genSpan = otel.getSpans().stream()
                .filter(s -> s.getName().equals("failing-gen")).findFirst().orElseThrow();
        assertThat(genSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(genSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
        assertThat(genSpan.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void callbackApi_spanInsideSpan() {
        var tracer = otel.getOpenTelemetry().getTracer("test");
        LangfuseOtel langfuse = new LangfuseOtel(null, otel.getOpenTelemetry(), null, true);

        langfuse.trace("nested-flow", trace -> {
            trace.span("preprocessing", span -> {
                span.input("raw data").output("processed data");
                span.generation("inner-gen", gen -> {
                    gen.model("gpt-4o-mini").input("processed data").output("result");
                });
            });
        });

        List<SpanData> spans = otel.getSpans();
        SpanData traceSpan = spans.stream().filter(s -> s.getName().equals("nested-flow")).findFirst().orElseThrow();
        SpanData preprocessSpan = spans.stream().filter(s -> s.getName().equals("preprocessing")).findFirst().orElseThrow();
        SpanData innerGen = spans.stream().filter(s -> s.getName().equals("inner-gen")).findFirst().orElseThrow();

        assertThat(preprocessSpan.getParentSpanId()).isEqualTo(traceSpan.getSpanContext().getSpanId());
        assertThat(innerGen.getParentSpanId()).isEqualTo(preprocessSpan.getSpanContext().getSpanId());
    }

    @Test
    void contextPropagation_appliedToTrace() {
        var tracer = otel.getOpenTelemetry().getTracer("test");

        LangfuseContext.setUserId("context-user");
        LangfuseContext.setSessionId("context-session");
        LangfuseContext.setTags("tag1", "tag2");
        LangfuseContext.setEnvironment("staging");

        try {
            try (LangfuseTrace trace = new LangfuseTrace(tracer, "context-trace")) {
                trace.input("test");
            }
        } finally {
            LangfuseContext.clear();
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("user.id"))).isEqualTo("context-user");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("session.id"))).isEqualTo("context-session");
        assertThat(span.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags"))).containsExactly("tag1", "tag2");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.environment"))).isEqualTo("staging");
    }

    @Test
    void noopMode_doesNotCrash() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder().build()) {
            assertThat(langfuse.isNoop()).isTrue();

            langfuse.trace("noop-trace", trace -> {
                trace.userId("user").sessionId("session");
                trace.generation("noop-gen", gen -> {
                    gen.model("gpt-4o")
                       .operationName("chat")
                       .input("hello")
                       .output("world")
                       .inputTokens(5)
                       .outputTokens(3);
                });
                trace.span("noop-span", span -> {
                    span.input("in").output("out");
                });
            });

            langfuse.flush();
        }
    }

    @Test
    void jsonUtils_escapesControlCharacters() {
        assertThat(JsonUtils.escapeJson(null)).isEqualTo("");
        assertThat(JsonUtils.escapeJson("hello")).isEqualTo("hello");
        assertThat(JsonUtils.escapeJson("line1\nline2")).isEqualTo("line1\\nline2");
        assertThat(JsonUtils.escapeJson("tab\there")).isEqualTo("tab\\there");
        assertThat(JsonUtils.escapeJson("quote\"here")).isEqualTo("quote\\\"here");
        assertThat(JsonUtils.escapeJson("back\\slash")).isEqualTo("back\\\\slash");
        assertThat(JsonUtils.escapeJson("bell\b")).isEqualTo("bell\\b");
        assertThat(JsonUtils.escapeJson("form\f")).isEqualTo("form\\f");
        String withNull = "null" + '\0' + "char";
        assertThat(JsonUtils.escapeJson(withNull)).isEqualTo("null\\u0000char");
    }

    private SpanData lastSpan() {
        List<SpanData> spans = otel.getSpans();
        return spans.get(spans.size() - 1);
    }

}
