package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Langfuse builds and owns its own {@link SdkTracerProvider}, separate from any provider installed by the
 * OpenTelemetry Java agent or the Micrometer tracing bridge. These tests pin down how Langfuse spans behave
 * relative to such an externally created parent span.
 */
class ExternalTraceLinkageTest {

    private final InMemorySpanExporter externalExporter = InMemorySpanExporter.create();
    private final InMemorySpanExporter langfuseExporter = InMemorySpanExporter.create();

    /** Mirrors the tracer provider wiring of {@code LangfuseOtel.Builder.build()}, exporting in memory. */
    private LangfuseOtel langfuseOtel() {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(new LangfuseContextSpanProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(langfuseExporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new LangfuseOtel(provider, sdk, null, false);
    }

    /** Stands in for a provider installed by the OTel Java agent or the Micrometer bridge. */
    private Tracer externalTracer(Sampler sampler) {
        return SdkTracerProvider.builder()
                .setSampler(sampler)
                .addSpanProcessor(SimpleSpanProcessor.create(externalExporter))
                .build()
                .get("simulated-otel-agent");
    }

    @Test
    void langfuseSpansJoinTheSurroundingTrace() {
        Tracer external = externalTracer(Sampler.alwaysOn());
        LangfuseOtel langfuse = langfuseOtel();

        Span httpSpan = external.spanBuilder("GET /chat").setSpanKind(SpanKind.SERVER).startSpan();
        String externalTraceId = httpSpan.getSpanContext().getTraceId();
        String externalSpanId = httpSpan.getSpanContext().getSpanId();

        try (Scope ignored = httpSpan.makeCurrent()) {
            langfuse.trace("chat-flow", t -> t.generation("llm-call", g -> g.model("gpt-4o")));
        }
        httpSpan.end();

        List<SpanData> exported = langfuseExporter.getFinishedSpanItems();
        SpanData trace = byName(exported, "chat-flow");
        SpanData generation = byName(exported, "llm-call");

        assertThat(trace.getTraceId()).isEqualTo(externalTraceId);
        assertThat(trace.getParentSpanId()).isEqualTo(externalSpanId);
        assertThat(generation.getTraceId()).isEqualTo(externalTraceId);
        assertThat(generation.getParentSpanId()).isEqualTo(trace.getSpanId());

        // The providers export to different backends, so neither side sees the whole trace.
        assertThat(names(externalExporter.getFinishedSpanItems())).containsExactly("GET /chat");
        assertThat(names(exported)).containsExactlyInAnyOrder("chat-flow", "llm-call");
    }

    @Test
    void langfuseSpansSurviveAnUpstreamSamplingDrop() {
        Tracer external = externalTracer(Sampler.alwaysOff());
        LangfuseOtel langfuse = langfuseOtel();

        Span httpSpan = external.spanBuilder("GET /chat").setSpanKind(SpanKind.SERVER).startSpan();
        String externalTraceId = httpSpan.getSpanContext().getTraceId();

        try (Scope ignored = httpSpan.makeCurrent()) {
            langfuse.trace("chat-flow", t -> t.generation("llm-call", g -> g.model("gpt-4o")));
        }
        httpSpan.end();

        assertThat(httpSpan.getSpanContext().isSampled()).isFalse();

        List<SpanData> exported = langfuseExporter.getFinishedSpanItems();
        assertThat(names(exported))
                .as("an explicit alwaysOn sampler keeps LLM observability independent of upstream head sampling")
                .containsExactlyInAnyOrder("chat-flow", "llm-call");
        assertThat(byName(exported, "chat-flow").getTraceId()).isEqualTo(externalTraceId);
    }

    /**
     * Guards the real {@code LangfuseOtel.builder()} wiring rather than the in-memory stand-in above.
     * The host is unroutable on purpose: the span never has to reach a collector for the sampling
     * decision — which is what this test asserts — to be observable.
     */
    @Test
    void builtProviderSamplesIndependentlyOfAnUnsampledParent() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-lf-test")
                .secretKey("sk-lf-test")
                .host("https://localhost:1")
                .failSafe(false)
                .build()) {

            assertThat(langfuse.isNoop()).isFalse();

            Span httpSpan = externalTracer(Sampler.alwaysOff())
                    .spanBuilder("GET /chat").setSpanKind(SpanKind.SERVER).startSpan();
            try (Scope ignored = httpSpan.makeCurrent()) {
                // Deliberately left unended: the sampling decision is made at startSpan(), and ending the
                // span would queue it for a delivery that this unroutable host can only fail slowly.
                Span generation = langfuse.getTracer().spanBuilder("llm-call").startSpan();
                assertThat(generation.getSpanContext().isSampled())
                        .as("sampling must not be inherited from the unsampled upstream parent")
                        .isTrue();
                assertThat(generation.isRecording()).isTrue();
            }
            httpSpan.end();
        }
    }

    @Test
    void langfuseStartsItsOwnTraceWithoutSurroundingContext() {
        LangfuseOtel langfuse = langfuseOtel();

        langfuse.trace("chat-flow", t -> t.generation("llm-call", g -> g.model("gpt-4o")));

        List<SpanData> exported = langfuseExporter.getFinishedSpanItems();
        SpanData trace = byName(exported, "chat-flow");

        assertThat(trace.getParentSpanContext().isValid()).isFalse();
        assertThat(byName(exported, "llm-call").getParentSpanId()).isEqualTo(trace.getSpanId());
    }

    private static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).collect(Collectors.toList());
    }

    private static SpanData byName(List<SpanData> spans, String name) {
        return spans.stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name + " in " + names(spans)));
    }
}
