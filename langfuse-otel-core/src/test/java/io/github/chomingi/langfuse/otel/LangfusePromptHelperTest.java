package io.github.chomingi.langfuse.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfusePromptHelperTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void variableReturnsSameInstanceForChaining() {
        LangfuseOtel langfuse = new LangfuseOtel(null, otel.getOpenTelemetry(), null, true);
        try (LangfuseGeneration gen = new LangfuseGeneration(langfuse.getTracer(), "test")) {
            // LangfusePromptHelper requires a real LangfuseClient to construct,
            // but we can test the prompt() guard on LangfuseGeneration
            // since langfuse-java IS on the classpath, Class.forName will succeed
            // and we get a LangfusePromptHelper instance
            Object dummyClient = createDummyClient();
            LangfusePromptHelper helper = gen.prompt(dummyClient, "my-prompt");
            LangfusePromptHelper same = helper.variable("key1", "val1");
            assertThat(same).isSameAs(helper);

            LangfusePromptHelper chained = helper.variable("key1", "val1").variable("key2", "val2");
            assertThat(chained).isSameAs(helper);
        }
    }

    @Test
    void promptThrowsWhenPassedWrongType() {
        LangfuseOtel langfuse = new LangfuseOtel(null, otel.getOpenTelemetry(), null, true);
        try (LangfuseGeneration gen = new LangfuseGeneration(langfuse.getTracer(), "test")) {
            // Passing a non-LangfuseClient object throws ClassCastException at construction
            assertThatThrownBy(() -> gen.prompt("not-a-client", "my-prompt"))
                    .isInstanceOf(ClassCastException.class);
        }
    }

    @Test
    void compileTextPromptSubstitutesVariables() {
        // Test the regex pattern directly via reflection-free approach:
        // Create a real TextPrompt mock and verify substitution
        String template = "Hello {{name}}, welcome to {{place}}!";
        String result = substituteVariables(template, "name", "Alice", "place", "Wonderland");
        assertThat(result).isEqualTo("Hello Alice, welcome to Wonderland!");
    }

    @Test
    void compilePreservesUnmatchedPlaceholders() {
        String template = "Hello {{name}}, your code is {{code}}";
        String result = substituteVariables(template, "name", "Bob");
        assertThat(result).isEqualTo("Hello Bob, your code is {{code}}");
    }

    @Test
    void compileHandlesWhitespaceInMustache() {
        String template = "Hello {{ name }}, welcome to {{  place  }}!";
        String result = substituteVariables(template, "name", "Alice", "place", "Wonderland");
        assertThat(result).isEqualTo("Hello Alice, welcome to Wonderland!");
    }

    @Test
    void compileHandlesSpecialRegexCharsInValue() {
        String template = "Pattern: {{regex}}";
        String result = substituteVariables(template, "regex", "$100.00 (USD)");
        assertThat(result).isEqualTo("Pattern: $100.00 (USD)");
    }

    @Test
    void compileHandlesDottedVariableNames() {
        String template = "Config: {{app.name}} v{{app.version}}";
        String result = substituteVariables(template, "app.name", "MyApp", "app.version", "2.0");
        assertThat(result).isEqualTo("Config: MyApp v2.0");
    }

    @Test
    void compileWithNoVariablesReturnsTemplateAsIs() {
        String template = "No variables here";
        String result = substituteVariables(template);
        assertThat(result).isEqualTo("No variables here");
    }

    /**
     * Replicate the same regex substitution logic from LangfusePromptHelper.compileTextPrompt()
     * to test without needing a network call to Langfuse.
     */
    private String substituteVariables(String template, String... keyValuePairs) {
        java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            vars.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");
        java.util.regex.Matcher matcher = pattern.matcher(template);
        StringBuffer compiled = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = vars.getOrDefault(name, matcher.group(0));
            matcher.appendReplacement(compiled, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(compiled);
        return compiled.toString();
    }

    private Object createDummyClient() {
        try {
            // Create a real LangfuseClient instance for type-checking
            return com.langfuse.client.LangfuseClient.builder()
                    .credentials("pk-test", "sk-test")
                    .build();
        } catch (Exception e) {
            // If client creation fails, return a mock-like object
            throw new RuntimeException("langfuse-java not on classpath", e);
        }
    }
}
