package com.example;

import com.langfuse.client.LangfuseClient;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class CorePromptClasspathTest {

    @Test
    void initializesExporterAndPromptClientOnTheSameConsumerClasspath() {
        LangfuseClient promptClient = LangfuseClient.builder()
                .credentials("pk-test", "sk-test")
                .url("http://127.0.0.1:1")
                .build();

        try (LangfuseOtel langfuse = LangfuseOtel.builder()
                .publicKey("pk-test")
                .secretKey("sk-test")
                .host("http://127.0.0.1:1")
                .allowInsecureHttpForDevelopment(true)
                .langfuseClient(promptClient)
                .failSafe(false)
                .build()) {
            assertFalse(langfuse.isNoop());
            assertSame(promptClient, langfuse.getLangfuseClient());
        }
    }
}
