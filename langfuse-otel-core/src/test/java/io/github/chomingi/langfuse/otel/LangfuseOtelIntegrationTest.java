package io.github.chomingi.langfuse.otel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class LangfuseOtelIntegrationTest {

    static final String PUBLIC_KEY = System.getenv("LANGFUSE_PUBLIC_KEY");
    static final String SECRET_KEY = System.getenv("LANGFUSE_SECRET_KEY");
    static final String HOST = System.getenv().getOrDefault("LANGFUSE_HOST", "https://cloud.langfuse.com");
    static final String PROMPT_NAME = System.getenv("LANGFUSE_TEST_PROMPT_NAME");

    @Test
    void sendFullTraceToLangfuse() throws Exception {
        requireCredentials();
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey(PUBLIC_KEY)
                .secretKey(SECRET_KEY)
                .host(HOST)
                .serviceName("langfuse-otel-java-test")
                .build()) {

            try (LangfuseTrace trace = langfuse.trace("integration-test-flow")
                    .userId("test-user-mingi")
                    .sessionId("integration-test-session")
                    .tags("test", "integration")
                    .input("인사평가 기준을 요약해줘")) {

                try (LangfuseSpan fetchSpan = trace.span("fetch-prompt")
                        .input("otel-test-prompt")
                        .output("당신은 {{domain}} 전문가입니다.")) {
                    Thread.sleep(50);
                }

                try (LangfuseGeneration gen = trace.generation("gpt-4o-call")
                        .model("gpt-4o")
                        .system("openai")
                        .temperature(0.7)
                        .maxTokens(1024)
                        .promptName("otel-test-prompt")
                        .promptVersion(1)
                        .input("[{\"role\":\"system\",\"content\":\"당신은 인사평가 전문가입니다.\"},{\"role\":\"user\",\"content\":\"MBO 평가 기준을 알려줘\"}]")) {
                    Thread.sleep(300);
                    gen.output("MBO 평가 기준: 1. 목표 달성률 (40%) 2. 과정 평가 (30%) 3. 역량 발휘 (30%)")
                       .responseModel("gpt-4o-2024-08-06")
                       .inputTokens(52)
                       .outputTokens(85)
                       .totalTokens(137);
                }

                try (LangfuseSpan postSpan = trace.span("format-response")
                        .input("raw LLM output")
                        .output("포맷팅된 MBO 평가 기준")) {
                    Thread.sleep(100);
                }

                trace.output("MBO 평가 기준 요약 완료");
            }

            langfuse.flush();
        }
    }

    @Test
    void sendTraceWithCallbackApi() throws Exception {
        requireCredentials();
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey(PUBLIC_KEY)
                .secretKey(SECRET_KEY)
                .host(HOST)
                .serviceName("langfuse-otel-java-test")
                .build()) {

            langfuse.trace("callback-api-test", trace -> {
                trace.userId("test-user-mingi")
                     .sessionId("callback-test-session")
                     .input("콜백 API 테스트");

                trace.generation("summarize-call", gen -> {
                    gen.model("gpt-4o")
                       .system("openai")
                       .temperature(0.5)
                       .input("이 문서를 요약해줘")
                       .output("요약 결과입니다.")
                       .inputTokens(30)
                       .outputTokens(15);
                });

                trace.span("post-process", span -> {
                    span.input("요약 결과")
                        .output("최종 결과");
                });

                trace.output("콜백 API 테스트 완료");
            });

            langfuse.flush();
        }
    }

    @Test
    void errorAutoCapture() throws Exception {
        requireCredentials();
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey(PUBLIC_KEY)
                .secretKey(SECRET_KEY)
                .host(HOST)
                .serviceName("langfuse-otel-java-test")
                .build()) {

            assertThatThrownBy(() -> {
                langfuse.trace("error-capture-test", trace -> {
                    trace.userId("test-user-mingi")
                         .sessionId("error-test-session")
                         .input("에러 테스트");

                    trace.generation("failing-llm-call", gen -> {
                        gen.model("gpt-4o")
                           .system("openai")
                           .input("이건 실패할 호출");

                        throw new RuntimeException("OpenAI API timeout: connection refused");
                    });
                });
            }).isInstanceOf(RuntimeException.class)
              .hasMessageContaining("timeout");

            langfuse.flush();
        }
    }

    @Test
    void threadLocalContext() throws Exception {
        requireCredentials();
        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey(PUBLIC_KEY)
                .secretKey(SECRET_KEY)
                .host(HOST)
                .serviceName("langfuse-otel-java-test")
                .build()) {

            LangfuseContext.setUserId("context-user-mingi");
            LangfuseContext.setSessionId("context-session-auto");
            LangfuseContext.setTags("context-test", "auto-propagation");
            LangfuseContext.setEnvironment("test");

            try {
                langfuse.trace("threadlocal-context-test", trace -> {
                    trace.input("ThreadLocal에서 userId/sessionId 자동 전파 테스트");

                    trace.generation("auto-context-gen", gen -> {
                        gen.model("gpt-4o")
                           .input("컨텍스트 자동 전파 확인")
                           .output("userId와 sessionId가 자동으로 설정됨")
                           .inputTokens(20)
                           .outputTokens(15);
                    });

                    trace.output("ThreadLocal 컨텍스트 테스트 완료");
                });
            } finally {
                LangfuseContext.clear();
            }

            langfuse.flush();
        }
    }

    @Test
    void promptIntegration() throws Exception {
        requireCredentials();
        assumeTrue(PROMPT_NAME != null && !PROMPT_NAME.isBlank(),
                "LANGFUSE_TEST_PROMPT_NAME is required for prompt integration tests");
        com.langfuse.client.LangfuseClient langfuseClient = com.langfuse.client.LangfuseClient.builder()
                .url(HOST)
                .credentials(PUBLIC_KEY, SECRET_KEY)
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey(PUBLIC_KEY)
                .secretKey(SECRET_KEY)
                .host(HOST)
                .serviceName("langfuse-otel-java-test")
                .langfuseClient(langfuseClient)
                .build()) {

            langfuse.trace("prompt-integration-test", trace -> {
                trace.userId("test-user-mingi")
                     .sessionId("prompt-test-session");

                trace.generation("prompt-compiled-gen", gen -> {
                    String compiled = gen.prompt(langfuseClient, PROMPT_NAME)
                            .variable("domain", "인사평가")
                            .variable("question", "역량 평가 항목을 알려줘")
                            .compile();

                    gen.model("gpt-4o")
                       .system("openai")
                       .output("역량 평가 항목: 리더십, 커뮤니케이션, 문제해결, 전문성")
                       .inputTokens(45)
                       .outputTokens(25);

                    System.out.println("  컴파일된 프롬프트: " + compiled);
                });

                trace.output("프롬프트 연동 테스트 완료");
            });

            langfuse.flush();
        }
    }

    private static void requireCredentials() {
        assumeTrue(PUBLIC_KEY != null && !PUBLIC_KEY.isBlank()
                        && SECRET_KEY != null && !SECRET_KEY.isBlank(),
                "LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY are required for integration tests");
    }
}
