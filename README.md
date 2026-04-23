<div align="center">

# langfuse-otel-java

**Zero-config LLM observability for Java applications**

Trace every LLM call to [Langfuse](https://langfuse.com) via [OpenTelemetry](https://opentelemetry.io/) — with one dependency.

[![Java 11+](https://img.shields.io/badge/Java-11%2B-blue)](https://openjdk.org/)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-enabled-blueviolet)](https://opentelemetry.io/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-auto--instrumented-brightgreen)](https://spring.io/projects/spring-ai)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-auto--instrumented-brightgreen)](https://github.com/langchain4j/langchain4j)

[Quick Start](#quick-start) · [Spring AI](#spring-ai--zero-code-tracing) · [LangChain4j](#langchain4j--zero-code-tracing) · [Features](#features) · [Compatibility](#compatibility)

</div>

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                Your Application                  │
│                                                  │
│  ┌──────────┐  ┌─────────────┐  ┌────────────┐  │
│  │ Spring AI │  │ LangChain4j │  │ Direct API │  │
│  └─────┬────┘  └──────┬──────┘  └─────┬──────┘  │
│        │              │               │          │
│        ▼              ▼               ▼          │
│  ┌──────────────────────────────────────────┐    │
│  │        langfuse-otel-java                │    │
│  │                                          │    │
│  │  • Auto-instrumentation (AOP)            │    │
│  │  • gen_ai.* semantic conventions         │    │
│  │  • Langfuse auth & endpoint config       │    │
│  │  • Error capture, context propagation    │    │
│  └─────────────────┬────────────────────────┘    │
│                    │                             │
└────────────────────┼─────────────────────────────┘
                     │ OTLP/HTTP
                     ▼
            ┌─────────────────┐
            │    Langfuse      │
            │                  │
            │  Traces, Costs,  │
            │  Prompts, Evals  │
            └─────────────────┘
```

<!-- TODO: Replace with actual Langfuse dashboard screenshot -->
<!-- ![Langfuse Dashboard](docs/images/langfuse-trace-screenshot.png) -->

---

## Why?

Java LLM frameworks (Spring AI, LangChain4j) lack a simple way to send traces to Langfuse. Without this library:

```java
// 😩 Raw OpenTelemetry — 40+ lines of boilerplate
String authHeader = "Basic " + Base64.getEncoder().encodeToString((pk + ":" + sk).getBytes());
OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
    .setEndpoint(host + "/api/public/otel/v1/traces")
    .addHeader("Authorization", authHeader)
    .addHeader("x-langfuse-ingestion-version", "4").build();
SdkTracerProvider provider = SdkTracerProvider.builder()
    .setResource(Resource.builder().put("service.name", name).build())
    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build()).build();
// ... 20 more lines for spans, attributes, cleanup
```

With this library:

```java
// ✅ 5 lines
langfuse.trace("my-flow", trace -> {
    trace.generation("llm-call", gen -> {
        gen.model("gpt-4o").input(prompt);
        gen.output(callLLM(prompt)).inputTokens(52).outputTokens(85);
    });
});
```

Or with Spring Boot — **zero lines of code**. Just add the dependency + `application.yml`.

---

## Quick Start

### Spring Boot (Recommended)

```xml
<dependency>
    <groupId>io.github.chomingi</groupId>
    <artifactId>langfuse-otel-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

```yaml
# application.yml
langfuse:
  public-key: ${LANGFUSE_PUBLIC_KEY}
  secret-key: ${LANGFUSE_SECRET_KEY}
  host: https://cloud.langfuse.com
  service-name: my-app
```

That's it. If you're using Spring AI or LangChain4j, all LLM calls are **automatically traced**.

### Standalone (No Spring)

```xml
<dependency>
    <groupId>io.github.chomingi</groupId>
    <artifactId>langfuse-otel-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
try (LangfuseOtel langfuse = LangfuseOtel.builder()
        .publicKey("pk-lf-...").secretKey("sk-lf-...")
        .host("https://cloud.langfuse.com")
        .serviceName("my-app").build()) {

    langfuse.trace("my-flow", trace -> {
        trace.userId("user-123").sessionId("session-456");

        trace.generation("llm-call", gen -> {
            gen.model("gpt-4o").system("openai").temperature(0.7);
            gen.input(prompt);
            String result = callLLM(prompt);
            gen.output(result).inputTokens(52).outputTokens(85);
        });
    });

    langfuse.flush();
}
```

---

## Spring AI — Zero Code Tracing

If `spring-ai` is on the classpath, **every `ChatModel.call()` is automatically traced**. No code changes needed.

```java
// Your existing code — completely unchanged
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
// → Automatically appears in Langfuse as a Generation with model, tokens, latency
```

**Auto-captured attributes:**

| Attribute | Source |
|-----------|--------|
| Model name | `ChatResponse.getMetadata().getModel()` |
| Input messages | `Prompt.getInstructions()` |
| Output text | `Generation.getOutput().getText()` |
| Input/Output tokens | `Usage.getPromptTokens()` / `getCompletionTokens()` |
| Temperature, max tokens | `ChatOptions` |
| Errors | Exception auto-capture with stack trace |

---

## LangChain4j — Zero Code Tracing

Same zero-config experience for LangChain4j:

```java
// Your existing code — completely unchanged
@Service
public class MyLangChain4jService {
    private final ChatModel chatModel;

    public MyLangChain4jService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String ask(String question) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(question)).build();
        return chatModel.chat(request).aiMessage().text();
    }
}
// → Automatically appears in Langfuse as a Generation
```

---

## Features

### 3 Ways to Trace

```java
// 1. Callback (cleanest — recommended)
langfuse.trace("flow", trace -> {
    trace.generation("llm", gen -> {
        gen.model("gpt-4o").output(callLLM());
    });
});

// 2. Try-with-resources (Java convention)
try (LangfuseTrace trace = langfuse.trace("flow")) {
    try (LangfuseGeneration gen = trace.generation("llm")) {
        gen.model("gpt-4o").output(callLLM());
    }
}

// 3. Manual end() (async / complex flows)
LangfuseGeneration gen = trace.generation("llm").model("gpt-4o");
gen.output(result).end();
```

### @ObserveGeneration Annotation

```java
@Service
public class LLMService {

    @ObserveGeneration(name = "summarize", model = "gpt-4o", system = "openai")
    public String summarize(String text) {
        return callLLM(text);
    }
}
```

### Automatic Error Capture

```java
langfuse.trace("flow", trace -> {
    trace.generation("llm", gen -> {
        gen.model("gpt-4o");
        throw new RuntimeException("API timeout");
        // → Span automatically marked as ERROR
        // → Exception message + stack trace recorded
        // → Exception re-thrown (not swallowed)
    });
});
```

### ThreadLocal Context Propagation

```java
// Set once in a filter/interceptor
LangfuseContext.setUserId("user-123");
LangfuseContext.setSessionId("session-456");
LangfuseContext.setTags("prod", "v2");

// All traces in this thread automatically inherit userId, sessionId, tags
langfuse.trace("flow", trace -> {
    // trace.userId is already "user-123" — no need to set it again
    trace.generation("llm", gen -> { ... });
});

// Spring Boot: LangfuseContextFilter auto-extracts from request principal + session
```

### Prompt Management Integration

Works with [langfuse-java](https://github.com/langfuse/langfuse-java) SDK:

```java
langfuse.trace("flow", trace -> {
    trace.generation("llm", gen -> {
        // Fetch prompt → compile variables → auto-link to generation span
        String compiled = gen.prompt(langfuseClient, "my-prompt")
                .variable("domain", "HR")
                .variable("question", "What is MBO?")
                .compile();
        // → promptName, promptVersion automatically set on span
        // → compiled prompt set as input

        gen.output(callLLM(compiled));
    });
});
```

---

## Modules

| Module | Java | Description |
|--------|------|-------------|
| `langfuse-otel-core` | 11+ | Core library — no framework dependency |
| `langfuse-otel-spring-boot-starter` | 17+ | Auto-config, Spring AI / LangChain4j instrumentation, `@ObserveGeneration` |

## Langfuse Attributes

All attributes follow [OTel GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/) and are recognized by Langfuse:

| Attribute | Set by | Langfuse mapping |
|-----------|--------|-----------------|
| `gen_ai.operation.name` | Auto (`"chat"`) | Observation type → Generation |
| `gen_ai.request.model` | `.model()` | Model name |
| `gen_ai.system` | `.system()` | Provider |
| `gen_ai.usage.input_tokens` | `.inputTokens()` | Token usage |
| `gen_ai.usage.output_tokens` | `.outputTokens()` | Token usage |
| `langfuse.observation.input` | `.input()` | Input |
| `langfuse.observation.output` | `.output()` | Output |
| `langfuse.observation.prompt.name` | `.promptName()` | Prompt link |
| `user.id` | `.userId()` | User |
| `session.id` | `.sessionId()` | Session |
| `langfuse.trace.tags` | `.tags()` | Tags |
| `langfuse.environment` | `.environment()` | Environment |

## Configuration Properties (Spring Boot)

| Property | Default | Description |
|----------|---------|-------------|
| `langfuse.public-key` | — | Langfuse public key (required) |
| `langfuse.secret-key` | — | Langfuse secret key (required) |
| `langfuse.host` | `https://cloud.langfuse.com` | Langfuse host URL |
| `langfuse.service-name` | `langfuse-app` | Service name in traces |
| `langfuse.environment` | — | Environment (e.g., `production`) |
| `langfuse.release` | — | Release version |
| `langfuse.enabled` | `true` | Enable/disable tracing |

## Compatibility

| Dependency | Tested Version | Notes |
|-----------|---------------|-------|
| Java | 11+ | Core module |
| Java | 17+ | Spring Boot starter |
| OpenTelemetry SDK | 1.44.1 | Via BOM |
| Spring Boot | 3.4.x | Auto-configuration |
| Spring AI | 1.0.0 | Auto-instrumentation |
| LangChain4j | 1.0.0 | Auto-instrumentation |
| langfuse-java | 0.2.x | Prompt integration (optional) |
| Langfuse Cloud | v3+ | OTLP ingestion endpoint |
| Langfuse Self-hosted | v3.22.0+ | OTLP support required |

## Examples

See the [examples](./examples) directory:
- [Spring AI Example](./examples/spring-ai-example) — Spring AI + OpenAI with zero-code tracing
- [LangChain4j Example](./examples/langchain4j-example) — LangChain4j + OpenAI with zero-code tracing

## Contributing

Contributions are welcome! Please open an issue first to discuss what you'd like to change.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

## License

MIT
