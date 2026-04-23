package io.github.chomingi.langfuse.otel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseOtelTest {

    @Test
    void builder_requiresPublicKey_failSafeOff() {
        assertThatThrownBy(() -> LangfuseOtel.builder()
                .secretKey("sk-test")
                .failSafe(false)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicKey");
    }

    @Test
    void builder_requiresSecretKey_failSafeOff() {
        assertThatThrownBy(() -> LangfuseOtel.builder()
                .publicKey("pk-test")
                .failSafe(false)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretKey");
    }

    @Test
    void builder_noopWhenKeysAbsent() {
        try (LangfuseOtel langfuse = LangfuseOtel.builder().build()) {
            assertThat(langfuse.isNoop()).isTrue();
            assertThat(langfuse.getTracer()).isNotNull();
            // should work without throwing
            langfuse.trace("noop-test", trace -> {
                trace.generation("gen", gen -> gen.model("test"));
            });
        }
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
    void concurrentTraces() throws Exception {
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .build()) {

            int threadCount = 10;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        langfuse.trace("concurrent-trace-" + idx, trace -> {
                            trace.userId("user-" + idx);
                            trace.generation("gen-" + idx, gen -> {
                                gen.model("gpt-4o").input("input-" + idx).output("output-" + idx);
                            });
                        });
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(errors.get()).isZero();
        }
    }

    @Test
    void contextIsolationBetweenThreads() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicReference<String> thread1UserId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> thread2UserId = new java.util.concurrent.atomic.AtomicReference<>();

        new Thread(() -> {
            LangfuseContext.setUserId("user-A");
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            thread1UserId.set(LangfuseContext.getUserId());
            LangfuseContext.clear();
            latch.countDown();
        }).start();

        new Thread(() -> {
            LangfuseContext.setUserId("user-B");
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            thread2UserId.set(LangfuseContext.getUserId());
            LangfuseContext.clear();
            latch.countDown();
        }).start();

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(thread1UserId.get()).isEqualTo("user-A");
        assertThat(thread2UserId.get()).isEqualTo("user-B");
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
