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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    void fluentTraceAttributesPropagateToSubsequentLibraryAndRawChildren() {
        langfuse.trace("fluent-root", trace -> {
            trace.userId("fluent-user")
                    .sessionId("fluent-session")
                    .tags("contract", "v4")
                    .metadata("tenant", "acme")
                    .version("prompt-v3")
                    .release("2026.07")
                    .environment("production")
                    .input("root-only-input")
                    .output("root-only-output");

            trace.generation("library-child", generation -> generation.model("gpt-4o-mini"));

            Span rawChild = langfuse.getTracer().spanBuilder("raw-child").startSpan();
            rawChild.end();
        });

        for (String name : List.of("library-child", "raw-child")) {
            SpanData child = findSpan(name);
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                    .isEqualTo("fluent-root");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                    .isEqualTo("fluent-user");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_SESSION_ID)))
                    .isEqualTo("fluent-session");
            assertThat(child.getAttributes().get(AttributeKey.stringArrayKey(LangfuseAttributes.TRACE_TAGS)))
                    .containsExactly("contract", "v4");
            assertThat(child.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA + ".tenant")))
                    .isEqualTo("acme");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.VERSION)))
                    .isEqualTo("prompt-v3");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.RELEASE)))
                    .isEqualTo("2026.07");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.ENVIRONMENT)))
                    .isEqualTo("production");
            assertThat(child.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_INPUT))).isNull();
            assertThat(child.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.OBSERVATION_OUTPUT))).isNull();
        }
    }

    @Test
    void nestedTraceSharesOuterTraceStateWithoutFreezingIt() {
        langfuse.trace("outer", outer -> {
            outer.userId("outer-user").metadata("scope", "outer");

            langfuse.trace("inner", inner -> {
                inner.userId("inner-user").metadata("scope", "inner");
                inner.generation("inner-child", generation -> {});
            });

            outer.userId("outer-final");
            outer.generation("outer-after-inner", generation -> {});
        });

        SpanData outerSpan = findSpan("outer");
        SpanData innerSpan = findSpan("inner");
        SpanData innerChild = findSpan("inner-child");
        assertThat(innerSpan.getTraceId()).isEqualTo(outerSpan.getTraceId());
        assertThat(innerChild.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                .isEqualTo("outer");
        assertThat(innerChild.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                .isEqualTo("inner-user");
        assertThat(innerChild.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA + ".scope")))
                .isEqualTo("inner");

        SpanData outerChild = findSpan("outer-after-inner");
        assertThat(outerChild.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                .isEqualTo("outer");
        assertThat(outerChild.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                .isEqualTo("outer-final");
        assertThat(outerChild.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA + ".scope")))
                .isEqualTo("inner");
    }

    @Test
    void traceUpdatesAffectOnlyChildrenStartedAfterTheSetterReturns() {
        langfuse.trace("update-boundary", trace -> {
            trace.generation("before-update", generation -> {});
            trace.userId("updated-user");
            trace.generation("after-update", generation -> {});
        });

        assertThat(findSpan("before-update").getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID))).isNull();
        assertThat(findSpan("after-update").getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID))).isEqualTo("updated-user");
    }

    @Test
    void closingTraceFreezesItsCapturedState() {
        LangfuseTrace trace = langfuse.trace("frozen-state");
        Context captured = Context.current();
        trace.close();

        trace.userId("too-late");
        Span lateChild = langfuse.getTracer().spanBuilder("late-captured-child")
                .setParent(captured)
                .startSpan();
        lateChild.end();

        SpanData child = findSpan("late-captured-child");
        assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                .isEqualTo("frozen-state");
        assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                .isNull();
    }

    @Test
    void capturedOtelContextPropagatesAcrossThreadWithoutWorkerLeak() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            langfuse.trace("async-root", trace -> {
                trace.userId("async-user").metadata("path", "captured");
                Context captured = Context.current();

                Future<?> completed = executor.submit(() -> {
                    try (Scope ignored = captured.makeCurrent()) {
                        Span child = langfuse.getTracer().spanBuilder("async-child").startSpan();
                        child.end();
                    }
                    Span unrelated = langfuse.getTracer().spanBuilder("post-task").startSpan();
                    unrelated.end();
                });
                try {
                    completed.get();
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        SpanData asyncChild = findSpan("async-child");
        assertThat(asyncChild.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                .isEqualTo("async-root");
        assertThat(asyncChild.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                .isEqualTo("async-user");
        assertThat(asyncChild.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA + ".path")))
                .isEqualTo("captured");

        SpanData postTask = findSpan("post-task");
        assertThat(postTask.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                .isNull();
        assertThat(postTask.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                .isNull();
    }

    @Test
    void capturedTraceMutationsDoNotLeakIntoWorkerThreadLocals() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            langfuse.trace("mutable-async-root", trace -> {
                Context captured = Context.current();
                Future<?> completed = executor.submit(() -> {
                    try (Scope ignored = captured.makeCurrent()) {
                        LangfuseContext.setUserId("worker-user");
                        LangfuseContext.setSessionId("worker-session");
                        LangfuseContext.setTags("worker-tag");
                        LangfuseContext.setEnvironment("worker-environment");
                        LangfuseContext.clear();
                    }
                    assertThat(LangfuseContext.current().getUserId()).isNull();
                    assertThat(LangfuseContext.current().getSessionId()).isNull();
                    assertThat(LangfuseContext.current().getTags()).isEmpty();
                    assertThat(LangfuseContext.current().getEnvironment()).isNull();
                });
                try {
                    completed.get();
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void frozenCapturedTraceRejectsLegacyMutationWithoutWorkerLeak() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LangfuseTrace trace = langfuse.trace("frozen-worker-root");
            Context captured = Context.current();
            trace.close();

            Future<?> completed = executor.submit(() -> {
                try (Scope ignored = captured.makeCurrent()) {
                    LangfuseContext.setUserId("too-late");
                    assertThat(LangfuseContext.current().getUserId()).isNull();
                }
                assertThat(LangfuseContext.current().getUserId()).isNull();
            });
            completed.get();
        } finally {
            executor.shutdownNow();
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
                .traceName("explicit-trace")
                .metadata("tenant", "acme")
                .version("prompt-v2")
                .release("2026.07")
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
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                .isEqualTo("explicit-trace");
        assertThat(spanData.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA + ".tenant")))
                .isEqualTo("acme");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.VERSION)))
                .isEqualTo("prompt-v2");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.RELEASE)))
                .isEqualTo("2026.07");
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
    void managedScopePreservesTraceWideAttributesAcrossLegacyMutation() {
        LangfuseTraceContext request = LangfuseTraceContext.builder()
                .userId("request-user")
                .traceName("managed-trace")
                .metadata("tenant", "acme")
                .version("prompt-v4")
                .release("2026.08")
                .build();

        try (Scope ignored = LangfuseContext.makeCurrent(request)) {
            LangfuseContext.setUserId("replacement-user");

            Span span = langfuse.getTracer().spanBuilder("managed-context").startSpan();
            span.end();
        }

        SpanData spanData = findSpan("managed-context");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                .isEqualTo("replacement-user");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                .isEqualTo("managed-trace");
        assertThat(spanData.getAttributes().get(
                AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA + ".tenant")))
                .isEqualTo("acme");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.VERSION)))
                .isEqualTo("prompt-v4");
        assertThat(spanData.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.RELEASE)))
                .isEqualTo("2026.08");
    }

    @Test
    void traceStateOverridesManagedScopeForFluentUpdatesAndChildren() {
        LangfuseTraceContext request = LangfuseTraceContext.builder()
                .userId("request-user")
                .traceName("request-trace")
                .metadata("tenant", "request-tenant")
                .version("request-version")
                .release("request-release")
                .build();

        try (Scope ignored = LangfuseContext.makeCurrent(request)) {
            langfuse.trace("managed-root", trace -> {
                trace.userId("fluent-user")
                        .metadata("tenant", "fluent-tenant")
                        .version("fluent-version")
                        .release("fluent-release");

                assertThat(LangfuseContext.current().getUserId()).isEqualTo("fluent-user");
                assertThat(LangfuseContext.current().getTraceName()).isEqualTo("managed-root");

                trace.generation("managed-library-child", generation -> {});
                Span rawChild = langfuse.getTracer().spanBuilder("managed-raw-child").startSpan();
                rawChild.end();
            });
        }

        for (String name : List.of("managed-library-child", "managed-raw-child")) {
            SpanData child = findSpan(name);
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_NAME)))
                    .isEqualTo("managed-root");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.TRACE_USER_ID)))
                    .isEqualTo("fluent-user");
            assertThat(child.getAttributes().get(
                    AttributeKey.stringKey(LangfuseAttributes.TRACE_METADATA + ".tenant")))
                    .isEqualTo("fluent-tenant");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.VERSION)))
                    .isEqualTo("fluent-version");
            assertThat(child.getAttributes().get(AttributeKey.stringKey(LangfuseAttributes.RELEASE)))
                    .isEqualTo("fluent-release");
        }
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
