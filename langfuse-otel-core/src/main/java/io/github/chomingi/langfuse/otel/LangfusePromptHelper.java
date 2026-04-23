package io.github.chomingi.langfuse.otel;

import com.langfuse.client.LangfuseClient;
import com.langfuse.client.resources.prompts.types.Prompt;
import com.langfuse.client.resources.prompts.types.TextPrompt;
import com.langfuse.client.prompt.PromptCompiler;

import java.util.LinkedHashMap;
import java.util.Map;

public class LangfusePromptHelper {

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
            String compiled = PromptCompiler.compile(textPrompt, variables);

            generation.promptName(promptName);
            generation.promptVersion(version);
            generation.input(compiled);

            return compiled;
        }).orElseThrow(() -> new IllegalStateException(
                "Prompt '" + promptName + "' is not a text prompt. Chat prompt compilation via this helper is not yet supported."));
    }
}
