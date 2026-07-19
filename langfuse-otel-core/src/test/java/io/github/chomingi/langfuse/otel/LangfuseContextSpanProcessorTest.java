package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void spanProcessorReadsImmutableMetadataFromExplicitParentContext() {
        LangfuseContext.setUserId("wrong-thread-local-user");
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("otel-context-user")
                .sessionId("otel-context-session")
                .tags("reactive", "safe")
                .environment("production")
                .build();
        Context parent = LangfuseContext.storeIn(Context.root(), traceContext);

        Span span = langfuse.getTracer().spanBuilder("explicit-parent")
                .setParent(parent)
                .startSpan();
        span.end();

        SpanData spanData = findSpan("explicit-parent");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("user.id")))
                .isEqualTo("otel-context-user");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("session.id")))
                .isEqualTo("otel-context-session");
        assertThat(spanData.getAttributes().get(AttributeKey.stringArrayKey("langfuse.trace.tags")))
                .containsExactly("reactive", "safe");
    }

    @Test
    void explicitParentWithoutMetadataDoesNotInheritUnrelatedCurrentRequestMetadata() {
        LangfuseTraceContext unrelatedRequest = LangfuseTraceContext.builder()
                .userId("unrelated-user")
                .sessionId("unrelated-session")
                .build();

        try (Scope ignored = LangfuseContext.makeCurrent(unrelatedRequest)) {
            Span span = langfuse.getTracer().spanBuilder("explicit-unrelated-parent")
                    .setParent(Context.root())
                    .startSpan();
            span.end();
        }

        SpanData spanData = findSpan("explicit-unrelated-parent");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("user.id"))).isNull();
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("session.id"))).isNull();
    }

    @Test
    void explicitRootDoesNotInheritLegacyMetadataWhenCurrentContextIsRoot() {
        LangfuseContext.setUserId("legacy-user");

        Span span = langfuse.getTracer().spanBuilder("explicit-root")
                .setParent(Context.root())
                .startSpan();
        span.end();

        SpanData spanData = findSpan("explicit-root");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey("user.id"))).isNull();
    }

    @Test
    void settersAndClearOverrideImmutableMetadataWithinCurrentScope() {
        LangfuseContext.setUserId("previous-user");
        LangfuseTraceContext request = LangfuseTraceContext.builder()
                .userId("request-user")
                .sessionId("request-session")
                .build();

        try (Scope ignored = LangfuseContext.makeCurrent(request)) {
            LangfuseContext.setUserId("replacement-user");
            assertThat(LangfuseContext.getUserId()).isEqualTo("replacement-user");

            Span replacement = langfuse.getTracer().spanBuilder("replacement-context").startSpan();
            replacement.end();
            assertThat(findSpan("replacement-context").getAttributes()
                    .get(AttributeKey.stringKey("user.id"))).isEqualTo("replacement-user");

            LangfuseContext.clear();
            assertThat(LangfuseContext.getUserId()).isNull();
            assertThat(LangfuseContext.getSessionId()).isNull();

            Span cleared = langfuse.getTracer().spanBuilder("cleared-context").startSpan();
            cleared.end();
            assertThat(findSpan("cleared-context").getAttributes()
                    .get(AttributeKey.stringKey("user.id"))).isNull();
            assertThat(findSpan("cleared-context").getAttributes()
                    .get(AttributeKey.stringKey("session.id"))).isNull();
        }

        assertThat(LangfuseContext.getUserId()).isEqualTo("previous-user");
    }

    @Test
    void unmanagedImmutableScopeIgnoresLegacyMutationsWithoutLeakingAnOverride() {
        LangfuseContext.setUserId("legacy-user");
        LangfuseTraceContext firstRequest = LangfuseTraceContext.builder()
                .userId("first-request")
                .build();

        try (Scope ignored = LangfuseContext.storeIn(Context.root(), firstRequest).makeCurrent()) {
            LangfuseContext.setUserId("must-not-escape");
            LangfuseContext.clear();
            assertThat(LangfuseContext.getUserId()).isEqualTo("first-request");
        }

        assertThat(LangfuseContext.getUserId()).isEqualTo("legacy-user");

        LangfuseTraceContext secondRequest = LangfuseTraceContext.builder()
                .userId("second-request")
                .build();
        try (Scope ignored = LangfuseContext.storeIn(Context.root(), secondRequest).makeCurrent()) {
            assertThat(LangfuseContext.getUserId()).isEqualTo("second-request");
        }
    }

    @Test
    void makeCurrentRestoresPreviousThreadLocalAndOtelContext() {
        LangfuseContext.setUserId("previous-user");
        LangfuseTraceContext traceContext = LangfuseTraceContext.builder()
                .userId("request-user")
                .sessionId("request-session")
                .build();

        try (Scope ignored = LangfuseContext.makeCurrent(traceContext)) {
            assertThat(LangfuseContext.getUserId()).isEqualTo("request-user");
            assertThat(LangfuseContext.from(Context.current())).isSameAs(traceContext);
        }

        assertThat(LangfuseContext.getUserId()).isEqualTo("previous-user");
        assertThat(LangfuseContext.from(Context.current())).isNull();
    }

    @Test
    void restoringScopeRunsRestoreActionEvenWhenOtelScopeCloseFails() {
        boolean[] restored = {false};
        Scope throwingScope = () -> {
            throw new IllegalStateException("scope close failed");
        };
        Scope restoringScope = LangfuseContext.restoringScope(
                throwingScope, () -> restored[0] = true);

        assertThatThrownBy(restoringScope::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("scope close failed");
        assertThat(restored[0]).isTrue();
        assertThatCode(restoringScope::close).doesNotThrowAnyException();
    }

    private SpanData findSpan(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Span not found: " + name));
    }
}
