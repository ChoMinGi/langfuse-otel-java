package io.github.chomingi.langfuse.otel;

import com.langfuse.client.LangfuseClient;
import com.langfuse.client.resources.prompts.types.Prompt;
import com.langfuse.client.resources.prompts.types.TextPrompt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LangfusePromptHelper {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");

    private final LangfuseClient client;
    private final String promptName;
    private final LangfuseGeneration generation;
    private final Map<String, String> variables = new LinkedHashMap<>();

    LangfusePromptHelper(Object client, String promptName, LangfuseGeneration generation) {
        this.client = (LangfuseClient) client;
        this.promptName = promptName;
        this.generation = generation;
    }

    public LangfusePromptHelper variable(String key, String value) {
        variables.put(key, value);
        return this;
    }

    public String compile() {
        Prompt prompt = client.prompts().get(promptName);

        return prompt.getText().map(textPrompt -> {
            int version = textPrompt.getVersion();
            String compiled = compileTextPrompt(textPrompt);

            generation.promptName(promptName);
            generation.promptVersion(version);
            generation.input(compiled);

            return compiled;
        }).orElseThrow(() -> new IllegalStateException(
                "Prompt '" + promptName + "' is not a text prompt. Chat prompt compilation via this helper is not yet supported."));
    }

    private String compileTextPrompt(TextPrompt textPrompt) {
        Matcher matcher = VARIABLE_PATTERN.matcher(textPrompt.getPrompt());
        StringBuffer compiled = new StringBuffer();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String value = variables.getOrDefault(variableName, matcher.group(0));
            matcher.appendReplacement(compiled, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(compiled);
        return compiled.toString();
    }
}
