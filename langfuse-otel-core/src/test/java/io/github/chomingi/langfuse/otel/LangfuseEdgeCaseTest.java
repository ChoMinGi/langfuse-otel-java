package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseEdgeCaseTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void doubleClose_isIdempotent() {
        LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "double-close");
        gen.model("gpt-4o");
        gen.close();
        assertThatCode(gen::close).doesNotThrowAnyException();
        assertThatCode(gen::end).doesNotThrowAnyException();
    }

    @Test
    void endThenClose_isIdempotent() {
        LangfuseSpan span = new LangfuseSpan(otel.getOpenTelemetry().getTracer("test"), "end-then-close");
        span.end();
        assertThatCode(span::close).doesNotThrowAnyException();
    }

    @Test
    void responseModel_setsAttribute() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "resp-model")) {
            gen.model("gpt-4o").responseModel("gpt-4o-2024-08-06");
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("gpt-4o");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model"))).isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    void level_setsAttribute() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "level-test")) {
            gen.level("WARNING").statusMessage("rate limited");
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("WARNING");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message"))).isEqualTo("rate limited");
    }

    @Test
    void spanLevel_setsAttribute() {
        try (LangfuseSpan span = new LangfuseSpan(otel.getOpenTelemetry().getTracer("test"), "span-level")) {
            span.level("ERROR").statusMessage("something failed");
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level"))).isEqualTo("ERROR");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message"))).isEqualTo("something failed");
    }

    @Test
    void context_clearRemovesValues() {
        LangfuseContext.setUserId("user-1");
        LangfuseContext.setSessionId("session-1");
        LangfuseContext.setTags("tag1");
        LangfuseContext.setEnvironment("prod");

        assertThat(LangfuseContext.getUserId()).isEqualTo("user-1");

        LangfuseContext.clear();

        assertThat(LangfuseContext.getUserId()).isNull();
        assertThat(LangfuseContext.getSessionId()).isNull();
        assertThat(LangfuseContext.getTags()).isEmpty();
        assertThat(LangfuseContext.getEnvironment()).isNull();
    }

    @Test
    void context_getTagsReturnsEmptyWhenNotSet() {
        LangfuseContext.clear();
        assertThat(LangfuseContext.getTags()).isEmpty();
    }

    @Test
    void deepNesting_traceSpanSpanGeneration() {
        var tracer = otel.getOpenTelemetry().getTracer("test");

        try (LangfuseTrace trace = new LangfuseTrace(tracer, "root")) {
            try (LangfuseSpan span1 = new LangfuseSpan(tracer, "level-1")) {
                try (LangfuseSpan span2 = new LangfuseSpan(tracer, "level-2")) {
                    try (LangfuseGeneration gen = new LangfuseGeneration(tracer, "level-3-gen")) {
                        gen.model("gpt-4o").input("deep input").output("deep output");
                    }
                }
            }
        }

        List<SpanData> spans = otel.getSpans();
        List<String> names = spans.stream().map(SpanData::getName).collect(Collectors.toList());
        assertThat(names).contains("root", "level-1", "level-2", "level-3-gen");

        SpanData root = findSpan("root");
        SpanData l1 = findSpan("level-1");
        SpanData l2 = findSpan("level-2");
        SpanData l3 = findSpan("level-3-gen");

        assertThat(l1.getParentSpanId()).isEqualTo(root.getSpanContext().getSpanId());
        assertThat(l2.getParentSpanId()).isEqualTo(l1.getSpanContext().getSpanId());
        assertThat(l3.getParentSpanId()).isEqualTo(l2.getSpanContext().getSpanId());
        assertThat(l3.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void builder_failSafeFalse_throwsOnMissingKeys() {
        assertThatThrownBy(() -> LangfuseOtel.builder()
                .failSafe(false)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_trailingSlashHost() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .host("https://cloud.langfuse.com/")
                .build()) {
            assertThat(langfuse).isNotNull();
            assertThat(langfuse.isNoop()).isFalse();
        }
    }

    @Test
    void builder_environmentAndRelease_inResource() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .environment("staging")
                .release("v1.2.3")
                .build()) {
            assertThat(langfuse).isNotNull();
        }
    }

    @Test
    void promptHelper_throwsWhenLangfuseJavaAbsent() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "prompt-fail")) {
            assertThatThrownBy(() -> gen.prompt(new Object(), "some-prompt"))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void generation_topP_setsAttribute() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "top-p")) {
            gen.topP(0.9);
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.doubleKey("gen_ai.request.top_p"))).isEqualTo(0.9);
    }

    @Test
    void generation_metadata_setsNestedAttribute() {
        try (LangfuseGeneration gen = new LangfuseGeneration(otel.getOpenTelemetry().getTracer("test"), "meta-gen")) {
            gen.metadata("provider", "azure").metadata("region", "eastus");
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.provider"))).isEqualTo("azure");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.region"))).isEqualTo("eastus");
    }

    @Test
    void trace_metadata_setsNestedAttribute() {
        try (LangfuseTrace trace = new LangfuseTrace(otel.getOpenTelemetry().getTracer("test"), "meta-trace")) {
            trace.metadata("workflow", "evaluation");
        }

        SpanData span = lastSpan();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.trace.metadata.workflow"))).isEqualTo("evaluation");
    }

    @Test
    void span_metadata_setsNestedAttribute() {
        try (LangfuseSpan span = new LangfuseSpan(otel.getOpenTelemetry().getTracer("test"), "meta-span")) {
            span.metadata("step", "preprocessing");
        }

        SpanData spanData = lastSpan();
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.step"))).isEqualTo("preprocessing");
    }

    private SpanData lastSpan() {
        List<SpanData> spans = otel.getSpans();
        return spans.get(spans.size() - 1);
    }

    private SpanData findSpan(String name) {
        return otel.getSpans().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
