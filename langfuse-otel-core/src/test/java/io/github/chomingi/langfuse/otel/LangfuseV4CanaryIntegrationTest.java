package io.github.chomingi.langfuse.otel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("release-canary")
class LangfuseV4CanaryIntegrationTest {

    @Test
    void exportsV4HierarchyForApiReadBack() {
        String publicKey = requiredEnvironment("LANGFUSE_PUBLIC_KEY");
        String secretKey = requiredEnvironment("LANGFUSE_SECRET_KEY");
        String commit = requiredEnvironment("LANGFUSE_CANARY_COMMIT");
        String marker = requiredEnvironment("LANGFUSE_CANARY_MARKER");
        String release = requiredProperty("langfuse.canary.release");
        String host = System.getenv().getOrDefault(
                "LANGFUSE_HOST", "https://cloud.langfuse.com");

        assertThat(commit)
                .as("LANGFUSE_CANARY_COMMIT must be the full release commit SHA")
                .matches("[0-9a-f]{40}");
        assertThat(marker)
                .as("LANGFUSE_CANARY_MARKER must be safe to use in an observation name")
                .matches("[A-Za-z0-9._-]{1,80}");

        String traceName = "langfuse-otel-java-v4-canary-" + marker;
        String childName = traceName + "-child";
        String generationName = traceName + "-generation";

        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey(publicKey)
                .secretKey(secretKey)
                .host(host)
                .serviceName("langfuse-otel-java-release-canary")
                .failSafe(false)
                .build()) {

            langfuse.trace(traceName, trace -> {
                trace.userId("canary-user-" + marker)
                        .sessionId("canary-session-" + marker)
                        .tags("release-canary", marker)
                        .metadata("canary_marker", marker)
                        .version(commit)
                        .release(release)
                        .environment("release-canary")
                        .input("root-input-" + marker);

                trace.span(childName, child -> child
                        .input("child-input-" + marker)
                        .output("child-output-" + marker));

                trace.generation(generationName, generation -> generation
                        .model("canary-model")
                        .input("generation-input-" + marker)
                        .output("generation-output-" + marker)
                        .inputTokens(1)
                        .outputTokens(1)
                        .totalTokens(2));

                trace.output("root-output-" + marker);
            });

            langfuse.flush();
            LangfuseOtelStatus status = langfuse.getStatus();
            assertThat(status.getExportState())
                    .isEqualTo(LangfuseOtelStatus.ExportState.SUCCEEDED);
            assertThat(status.getFlushState())
                    .isEqualTo(LangfuseOtelStatus.FlushState.SUCCEEDED);
            assertThat(status.getQueueDroppedSpanCount()).isZero();
        }

        System.out.println("LANGFUSE_CANARY_TRACE_NAME=" + traceName);
        System.out.println("LANGFUSE_CANARY_ROOT_INPUT=root-input-" + marker);
        System.out.println("LANGFUSE_CANARY_ROOT_OUTPUT=root-output-" + marker);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value)
                .as("%s is required for the release canary", name)
                .isNotBlank();
        return value;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value)
                .as("-D%s is required for the release canary", name)
                .isNotBlank();
        return value;
    }
}
