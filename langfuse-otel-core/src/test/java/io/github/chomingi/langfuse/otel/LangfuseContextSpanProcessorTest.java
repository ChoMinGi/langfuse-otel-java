package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseContextSpanProcessorTest {

    private InMemorySpanExporter spanExporter;
    private LangfuseOtel langfuse;

    @BeforeEach
    void setup() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new LangfuseContextSpanProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetrySdk otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        langfuse = new LangfuseOtel(tracerProvider, otelSdk, null, true);
    }

    @AfterEach
    void cleanup() {
        LangfuseContext.clear();
        if (langfuse != null) langfuse.close();
    }

    @Test
    void childSpanInheritsContextAttributes() {
        LangfuseContext.setUserId("user-123");
        LangfuseContext.setSessionId("session-456");
        LangfuseContext.setTags("prod", "v2");
        LangfuseContext.setEnvironment("production");

        langfuse.trace("test-trace", trace -> {
            trace.generation("child-gen", gen -> {
                gen.model("gpt-4o").input("hello").output("world");
            });
        });

        SpanData traceSpan = findSpan("test-trace");
        SpanData genSpan = findSpan("child-gen");

        assertThat(traceSpan.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo("user-123");
        assertThat(genSpan.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo("user-123");
        assertThat(genSpan.getAttributes().get(AttributeKey.stringKey("session.id")))
                .isEqualTo("session-456");
        assertThat(genSpan.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags")))
                .containsExactly("prod", "v2");
        assertThat(genSpan.getAttributes().get(AttributeKey.stringKey("langfuse.environment")))
                .isEqualTo("production");
    }

    @Test
    void nestedSpansAllInheritContext() {
        LangfuseContext.setUserId("user-nested");
        LangfuseContext.setSessionId("session-nested");

        langfuse.trace("root", trace -> {
            trace.span("level1", span -> {
                span.generation("level2", gen -> {
                    gen.model("gpt-4o");
                });
            });
        });

        for (String name : List.of("root", "level1", "level2")) {
            SpanData span = findSpan(name);
            assertThat(span.getAttributes().get(AttributeKey.stringKey("user.id")))
                    .as("userId on span '%s'", name)
                    .isEqualTo("user-nested");
            assertThat(span.getAttributes().get(AttributeKey.stringKey("session.id")))
                    .as("sessionId on span '%s'", name)
                    .isEqualTo("session-nested");
        }
    }

    @Test
    void noContextSet_noAttributesOnSpan() {
        langfuse.trace("no-context", trace -> {
            trace.generation("gen-no-ctx", gen -> {
                gen.model("gpt-4o");
            });
        });

        SpanData genSpan = findSpan("gen-no-ctx");
        assertThat(genSpan.getAttributes().get(AttributeKey.stringKey("user.id"))).isNull();
        assertThat(genSpan.getAttributes().get(AttributeKey.stringKey("session.id"))).isNull();
        assertThat(genSpan.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags"))).isNull();
        assertThat(genSpan.getAttributes().get(AttributeKey.stringKey("langfuse.environment"))).isNull();
    }

    @Test
    void contextClearedMidTrace_newSpanHasNoAttributes() {
        LangfuseContext.setUserId("user-temp");

        langfuse.trace("clear-test", trace -> {
            trace.generation("before-clear", gen -> {
                gen.model("gpt-4o");
            });

            LangfuseContext.clear();

            trace.generation("after-clear", gen -> {
                gen.model("gpt-4o");
            });
        });

        SpanData beforeSpan = findSpan("before-clear");
        SpanData afterSpan = findSpan("after-clear");

        assertThat(beforeSpan.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo("user-temp");
        assertThat(afterSpan.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isNull();
    }

    private SpanData findSpan(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
