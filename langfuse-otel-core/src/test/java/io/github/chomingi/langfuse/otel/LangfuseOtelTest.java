package io.github.chomingi.langfuse.otel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseOtelTest {

    @Test
    void builder_requiresPublicKey() {
        assertThatThrownBy(() -> LangfuseOtel.builder()
                .secretKey("sk-test")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicKey");
    }

    @Test
    void builder_requiresSecretKey() {
        assertThatThrownBy(() -> LangfuseOtel.builder()
                .publicKey("pk-test")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretKey");
    }

    @Test
    void builder_createsInstance() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .serviceName("test-service")
                .build()) {
            assertThat(langfuse).isNotNull();
            assertThat(langfuse.getTracer()).isNotNull();
        }
    }

    @Test
    void builder_customHost() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .host("https://us.cloud.langfuse.com")
                .build()) {
            assertThat(langfuse).isNotNull();
        }
    }

    @Test
    void trace_createsAndCloses() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .build()) {

            try (LangfuseTrace trace = langfuse.trace("test-trace")) {
                assertThat(trace).isNotNull();
                assertThat(trace.getSpan()).isNotNull();
            }
        }
    }

    @Test
    void trace_fluent_api() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .build()) {

            try (LangfuseTrace trace = langfuse.trace("test-trace")
                    .userId("user-1")
                    .sessionId("session-1")
                    .tags("prod", "v2")
                    .input("hello")
                    .metadata("key", "value")) {
                assertThat(trace.getSpan().isRecording()).isTrue();
            }
        }
    }

    @Test
    void generation_setsAttributes() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .build()) {

            try (LangfuseTrace trace = langfuse.trace("test-trace")) {
                try (LangfuseGeneration gen = trace.generation("llm-call")
                        .model("gpt-4o")
                        .system("openai")
                        .temperature(0.7)
                        .maxTokens(1024)
                        .input("prompt text")
                        .promptName("my-prompt")
                        .promptVersion(1)) {
                    gen.output("response text");
                    gen.inputTokens(50);
                    gen.outputTokens(100);
                    assertThat(gen.getSpan().isRecording()).isTrue();
                }
            }
        }
    }

    @Test
    void span_nestedHierarchy() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .build()) {

            try (LangfuseTrace trace = langfuse.trace("parent")) {
                try (LangfuseSpan span = trace.span("child-1")
                        .input("input")
                        .output("output")) {
                    try (LangfuseGeneration gen = span.generation("nested-gen")
                            .model("gpt-4o")) {
                        assertThat(gen.getSpan().isRecording()).isTrue();
                    }
                }
            }
        }
    }
}
