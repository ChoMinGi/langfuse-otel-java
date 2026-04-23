# langfuse-otel-java

Java library for easy [OpenTelemetry](https://opentelemetry.io/) integration with [Langfuse](https://langfuse.com).

Wraps the OpenTelemetry SDK to automatically configure OTLP export to Langfuse with proper authentication, `gen_ai.*` semantic conventions, and Langfuse-specific attributes.

## Modules

| Module | Java | Description |
|--------|------|-------------|
| `langfuse-otel-core` | 11+ | Core library — no framework dependency |
| `langfuse-otel-spring-boot-starter` | 17+ | Spring Boot auto-configuration + `@ObserveGeneration` |

## Quick Start

### Standalone (Core)

```xml
<dependency>
    <groupId>io.github.chomingi</groupId>
    <artifactId>langfuse-otel-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
try (LangfuseOtel langfuse = LangfuseOtel.builder()
        .publicKey("pk-lf-...")
        .secretKey("sk-lf-...")
        .host("https://us.cloud.langfuse.com")
        .serviceName("my-app")
        .build()) {

    try (LangfuseTrace trace = langfuse.trace("my-flow")
            .userId("user-123")
            .sessionId("session-456")) {

        try (LangfuseGeneration gen = trace.generation("llm-call")
                .model("gpt-4o")
                .system("openai")
                .temperature(0.7)
                .input(prompt)) {
            String result = callLLM(prompt);
            gen.output(result);
            gen.inputTokens(52);
            gen.outputTokens(85);
        }
    }

    langfuse.flush();
}
```

### Spring Boot

```xml
<dependency>
    <groupId>io.github.chomingi</groupId>
    <artifactId>langfuse-otel-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

**application.yml:**

```yaml
langfuse:
  public-key: pk-lf-...
  secret-key: sk-lf-...
  host: https://us.cloud.langfuse.com
  service-name: my-spring-app
  enabled: true
```

**Using the bean:**

```java
@Service
public class MyService {
    private final LangfuseOtel langfuse;

    public MyService(LangfuseOtel langfuse) {
        this.langfuse = langfuse;
    }

    public String chat(String prompt) {
        try (LangfuseTrace trace = langfuse.trace("chat")
                .userId("user-123")) {
            try (LangfuseGeneration gen = trace.generation("llm-call")
                    .model("gpt-4o")) {
                gen.input(prompt);
                String result = callLLM(prompt);
                gen.output(result);
                return result;
            }
        }
    }
}
```

**Using `@ObserveGeneration`:**

```java
@Service
public class LLMService {

    @ObserveGeneration(name = "summarize", model = "gpt-4o", system = "openai")
    public String summarize(String text) {
        return callLLM(text);
    }
}
```

## Spring AI Auto-Instrumentation (Zero Code)

If Spring AI is on the classpath, **all `ChatModel.call()` invocations are automatically traced** — no code changes needed.

```java
// Your existing Spring AI code — unchanged
@Service
public class MyAiService {
    private final ChatModel chatModel;

    public MyAiService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String ask(String question) {
        return chatModel.call(new Prompt(question))
                .getResult().getOutput().getText();
    }
}
```

Every call automatically captures:
- Model name, temperature, max tokens
- Input messages (system, user, assistant)
- Output text
- Token usage (input/output/total)
- Latency
- Errors

All sent to Langfuse as a **Generation** span with proper `gen_ai.*` attributes.

## Langfuse Attributes

The library sets these OTel attributes automatically, which Langfuse recognizes:

| Attribute | Set by |
|-----------|--------|
| `gen_ai.operation.name` | `LangfuseGeneration` (auto: `"chat"`) |
| `gen_ai.request.model` | `.model()` |
| `gen_ai.system` | `.system()` |
| `gen_ai.usage.input_tokens` | `.inputTokens()` |
| `gen_ai.usage.output_tokens` | `.outputTokens()` |
| `langfuse.observation.input` | `.input()` |
| `langfuse.observation.output` | `.output()` |
| `langfuse.observation.prompt.name` | `.promptName()` |
| `user.id` | `.userId()` |
| `session.id` | `.sessionId()` |

## License

MIT
