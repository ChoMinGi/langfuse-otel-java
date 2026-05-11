<div align="center">

# langfuse-otel-java

**LLM observability for Java — zero config, one dependency.**

[![CI](https://github.com/ChoMinGi/langfuse-otel-java/actions/workflows/ci.yml/badge.svg)](https://github.com/ChoMinGi/langfuse-otel-java/actions)
[![Java Core 11%2B / Starter 17%2B](https://img.shields.io/badge/Java-core%2011%2B%20%7C%20starter%2017%2B-blue)](https://openjdk.org/)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-enabled-blueviolet)](https://opentelemetry.io/)

[Why this exists](#the-problem) · [Quick Start](#quick-start) · [What gets traced](#what-gets-traced) · [Features](#features) · [Compatibility](#compatibility)

</div>

---

## The Problem

[Langfuse](https://langfuse.com) is an open-source LLM observability platform — traces, costs, prompt management, and evaluations in one place. Python and TypeScript have first-class SDKs that make integration trivial.

Java doesn't.

If you're building LLM applications in Java with [Spring AI](https://spring.io/projects/spring-ai) or [LangChain4j](https://github.com/langchain4j/langchain4j), your options for Langfuse integration look like this:

```java
// Raw OpenTelemetry — 40+ lines of boilerplate for every project
String authHeader = "Basic " + Base64.getEncoder().encodeToString((pk + ":" + sk).getBytes());
OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
    .setEndpoint(host + "/api/public/otel/v1/traces")
    .addHeader("Authorization", authHeader)
    .addHeader("x-langfuse-ingestion-version", "4").build();
SdkTracerProvider provider = SdkTracerProvider.builder()
    .setResource(Resource.builder().put("service.name", name).build())
    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build()).build();
// ... and 20 more lines for spans, attributes, gen_ai conventions, cleanup
```

This library eliminates all of it.

---

## The Solution

```
┌─────────────────────────────────────────────────┐
│               Your Application                  │
│                                                 │
│  ┌──────────┐  ┌─────────────┐  ┌────────────┐  │
│  │ Spring AI│  │ LangChain4j │  │ Direct API │  │
│  └─────┬────┘  └──────┬──────┘  └─────┬──────┘  │
│        │              │               │         │
│        ▼              ▼               ▼         │
│  ┌──────────────────────────────────────────┐   │
│  │         langfuse-otel-java               │   │
│  │                                          │   │
│  │  Chat · Streaming · Embeddings · Images  │   │
│  │  Auto-instrumented · Zero config         │   │
│  └─────────────────┬────────────────────────┘   │
│                    │                            │
└────────────────────┼────────────────────────────┘
                     │ OTLP/HTTP
                     ▼
            ┌─────────────────┐
            │    Langfuse     │
            │ Traces · Costs  │
            │ Prompts · Evals │
            └─────────────────┘
```

Add one dependency, set three properties. Every LLM call — sync, streaming, embeddings, image generation — automatically appears in your Langfuse dashboard.

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
  host: https://cloud.langfuse.com   # or your self-hosted URL
```

Done. Your Spring AI and LangChain4j calls are now traced.

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
            gen.model("gpt-4o").input(prompt);
            gen.output(callLLM(prompt)).inputTokens(52).outputTokens(85);
        });
    });
}
```

---

## What Gets Traced

With the Spring Boot starter, the following models are **automatically instrumented** — no code changes required.

### Spring AI

| Model | Methods | Operation |
|-------|---------|-----------|
| `ChatModel` | `call(Prompt)` | `chat` |
| `ChatModel` | `stream(Prompt)` | `chat` (with TTFT) |
| `EmbeddingModel` | `call(EmbeddingRequest)` | `embeddings` |
| `ImageModel` | `call(ImagePrompt)` | `image_generation` |

```java
// Your existing code — completely unchanged
@Service
public class MyAiService {
    private final ChatModel chatModel;

    // Sync — traced automatically
    public String ask(String question) {
        return chatModel.call(new Prompt(question))
                .getResult().getOutput().getText();
    }

    // Streaming — traced automatically, with time-to-first-token
    public Flux<String> askStream(String question) {
        return chatModel.stream(new Prompt(question))
                .map(r -> r.getResult().getOutput().getText());
    }
}
```

### LangChain4j

| Model | Methods | Operation |
|-------|---------|-----------|
| `ChatModel` | `chat(ChatRequest)` | `chat` |
| `StreamingChatModel` | `chat(ChatRequest, Handler)` | `chat` (with TTFT) |
| `EmbeddingModel` | `embedAll(...)`, `embed(...)` | `embeddings` |
| `ImageModel` | `generate(...)` | `image_generation` |

```java
// Sync — traced automatically
chatModel.chat(ChatRequest.builder()
        .messages(UserMessage.from("Hello")).build());

// Streaming — traced automatically
streamingModel.chat("Hello", new StreamingChatResponseHandler() {
    @Override public void onPartialResponse(String token) { /* ... */ }
    @Override public void onCompleteResponse(ChatResponse response) { /* ... */ }
    @Override public void onError(Throwable error) { /* ... */ }
});
```

### Auto-captured attributes

| Attribute | Description |
|-----------|-------------|
| Model name | Request & response model |
| Input | Messages (chat), text (embeddings), prompt (image) |
| Output | Response text, accumulated stream, embedding/image count |
| Token usage | Input, output, and total tokens |
| Temperature, top_p, max_tokens | Model parameters |
| TTFT | Time to first token (streaming only) |
| Errors | Exception message + stack trace |

---

## Features

### @ObserveGeneration Annotation

Trace any method as an LLM generation — useful for custom LLM integrations:

```java
@Service
public class LLMService {
    @ObserveGeneration(name = "summarize", model = "gpt-4o", system = "openai")
    public String summarize(String text) {
        return callLLM(text);
    }
}
```

### ThreadLocal Context Propagation

```java
// Set once in a filter or interceptor
LangfuseContext.setUserId("user-123");
LangfuseContext.setSessionId("session-456");
LangfuseContext.setTags("prod", "v2");

// All traces on this thread automatically inherit these values
langfuse.trace("flow", trace -> { ... });

// Spring Boot: LangfuseContextFilter auto-extracts from HTTP requests
// Principal → userId, HttpSession → sessionId
```

### Prompt Management

Integrates with [langfuse-java](https://github.com/langfuse/langfuse-java) for prompt versioning:

```java
trace.generation("llm", gen -> {
    String compiled = gen.prompt(langfuseClient, "my-prompt")
            .variable("domain", "HR")
            .variable("question", "What is MBO?")
            .compile();
    // promptName & promptVersion auto-linked to the span
    gen.output(callLLM(compiled));
});
```

### 3 Tracing Styles

```java
// Callback (recommended)
langfuse.trace("flow", trace -> {
    trace.generation("llm", gen -> { gen.model("gpt-4o").output(callLLM()); });
});

// Try-with-resources
try (var trace = langfuse.trace("flow")) {
    try (var gen = trace.generation("llm")) { gen.model("gpt-4o").output(callLLM()); }
}

// Manual end()
var gen = trace.generation("llm").model("gpt-4o");
gen.output(result).end();
```

### Fail-safe by Default

Missing API keys? Misconfigured endpoint? The library switches to no-op mode silently. Your application never crashes because of observability.

---

## Modules

| Module | Java | Description |
|--------|------|-------------|
| `langfuse-otel-core` | 11+ | Core tracing library — no framework dependency |
| `langfuse-otel-spring-boot-starter` | 17+ | Auto-config for Spring AI & LangChain4j |

## Configuration (Spring Boot)

| Property | Default | Description |
|----------|---------|-------------|
| `langfuse.public-key` | — | Langfuse public key (required) |
| `langfuse.secret-key` | — | Langfuse secret key (required) |
| `langfuse.host` | `https://cloud.langfuse.com` | Langfuse host URL |
| `langfuse.service-name` | `langfuse-app` | Service name in traces |
| `langfuse.environment` | — | Environment (e.g., `production`) |
| `langfuse.release` | — | Release version |
| `langfuse.enabled` | `true` | Enable/disable all tracing |

## Compatibility

| Dependency | Tested Version | Notes |
|-----------|---------------|-------|
| Java | 11+ | Core module |
| Java | 17+ | Spring Boot starter |
| OpenTelemetry SDK | 1.44.1 | Via BOM |
| Spring Boot | 3.4.x | Auto-configuration |
| Spring AI | 1.0.0 | Chat, streaming, embeddings, images |
| LangChain4j | 1.0.0 | Chat, streaming, embeddings, images |
| langfuse-java | 0.2.x | Prompt management (optional) |
| Langfuse Cloud | v3+ | OTLP ingestion |
| Langfuse Self-hosted | v3.22.0+ | Requires OTLP support |

## Examples

See the [examples](./examples) directory:
- [Spring AI + OpenAI](./examples/spring-ai-example) — zero-code tracing
- [LangChain4j + OpenAI](./examples/langchain4j-example) — zero-code tracing

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

## License

MIT
