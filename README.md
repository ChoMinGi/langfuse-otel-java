<div align="center">

# langfuse-otel-java

OpenTelemetry-based Langfuse tracing for Java, Spring AI, and LangChain4j.

[![CI](https://github.com/ChoMinGi/langfuse-otel-java/actions/workflows/ci.yml/badge.svg)](https://github.com/ChoMinGi/langfuse-otel-java/actions)
[![Java Core 11%2B / Starter 17%2B](https://img.shields.io/badge/Java-core%2011%2B%20%7C%20starter%2017%2B-blue)](https://openjdk.org/)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-enabled-blueviolet)](https://opentelemetry.io/)

[Quick Start](#quick-start) · [What gets traced](#what-gets-traced) · [Features](#features) · [Compatibility](#compatibility) · [Migration](MIGRATION-0.2.md) · [Roadmap](ROADMAP.md)

</div>

The core module provides a small synchronous tracing API. The Spring Boot starter instruments
supported Spring AI and LangChain4j calls and can either reuse the application's OpenTelemetry SDK
or own a dedicated Langfuse exporter.

---

## Quick Start

`0.2.0-SNAPSHOT` is the current production-preview development line. Build it locally with `./mvnw clean install`; use `0.1.1` when resolving only from Maven Central until 0.2.0 is released.

### Spring Boot (Dedicated Exporter Quick Start)

```xml
<dependency>
    <groupId>io.github.chomingi</groupId>
    <artifactId>langfuse-otel-spring-boot-starter</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

```yaml
# application.yml
langfuse:
  otel-mode: standalone              # create and own a dedicated Langfuse exporter
  public-key: ${LANGFUSE_PUBLIC_KEY}
  secret-key: ${LANGFUSE_SECRET_KEY}
  host: https://cloud.langfuse.com   # or your self-hosted URL
  content:
    capture-input: false             # safe default
    capture-output: false            # safe default
```

This quick start deliberately selects `standalone`, so the configured keys and host are used by a dedicated SDK/exporter. For production applications that already own OpenTelemetry, use the external setup below instead of creating a second SDK.

Standalone endpoints must use HTTPS. For a loopback development receiver only, plaintext HTTP can be enabled explicitly with `langfuse.allow-insecure-http-for-development=true`; never use that option with production credentials.

### Standalone (No Spring)

```xml
<dependency>
    <groupId>io.github.chomingi</groupId>
    <artifactId>langfuse-otel-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
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

### Existing OpenTelemetry SDK (Production Recommended)

With the default `langfuse.otel-mode=auto`, the starter reuses exactly one application `OpenTelemetry` bean when available. It does not create an exporter and never flushes or shuts down the application-owned SDK. Configure that SDK or an OpenTelemetry Collector to export traces to Langfuse. If Langfuse keys are also configured, the starter warns that they are ignored in external mode.

Set `langfuse.otel-mode=external` to require one application bean, or `standalone` to force a dedicated Langfuse SDK/exporter. Ambiguous external bean configurations fail at startup instead of silently selecting the wrong telemetry pipeline.

Without Spring, select the same non-owning mode explicitly:

```java
try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(openTelemetry).build()) {
    langfuse.trace("my-flow", trace -> { /* ... */ });
}
// openTelemetry remains owned by the application
```

The original `builder()` remains the standalone mode: it creates and owns a dedicated SDK and Langfuse OTLP exporter.

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

Reactor scheduler transitions and downstream signals are bridged automatically. Provider adapters
that own a raw, plain-thread source can place
`ReactorContextPropagation.wrap(rawPublisher)` at that source boundary. See the
[async lifecycle design](DESIGN.md#asynchronous-observation-and-context-lifecycle) for the exact
boundary.

### LangChain4j

| Model | Methods | Operation |
|-------|---------|-----------|
| `ChatModel` | `chat(ChatRequest)` | `chat` |
| `StreamingChatModel` | `chat(ChatRequest, Handler)` | `chat` (with TTFT) |
| `EmbeddingModel` | `embedAll(...)`, `embed(...)` | `embeddings` |
| `ImageModel` | `generate(...)` | `image_generation` |

Streaming callbacks and model listeners restore the wrapper observation and immutable request
metadata. Provider adapters that control task submission can use
`LangChain4jStreamingContext.wrap(...)`, `taskWrapping(...)`, or a per-request `Snapshot`.
Opaque provider executors remain outside the generic SPI; the
[async lifecycle design](DESIGN.md#asynchronous-observation-and-context-lifecycle) describes that
limit.

### Auto-captured attributes

| Attribute | Description |
|-----------|-------------|
| Model name | Request & response model |
| Input | Disabled by default; messages, embedding text, or image prompt when opted in |
| Output | Disabled by default; response or accumulated stream when opted in |
| Token usage | Input, output, and total tokens |
| Temperature, top_p, max_tokens | Model parameters |
| TTFT | Time to first token (streaming only) |
| Errors | Exception type by default; policy-processed message/stack trace only after explicit opt-in |

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

`@ObserveGeneration` covers synchronous methods, `CompletionStage`, and Reactor `Mono`/`Flux`.
Stages retain their identity, and Reactor creates one observation per subscription. On a model
bean, explicit annotations take precedence over automatic instrumentation so the same call is not
traced twice.

### Request Context Propagation

```java
LangfuseTraceContext metadata = LangfuseTraceContext.builder()
        .userId("user-123")
        .sessionId("session-456")
        .tags("prod", "v2")
        .build();

try (Scope ignored = LangfuseContext.makeCurrent(metadata)) {
    langfuse.trace("flow", trace -> { /* ... */ });
}
```

Spring MVC and WebFlux filters can also extract Principal and HTTP session metadata after explicit
opt-in. The legacy `LangfuseContext.set*()` methods remain available for synchronous code.

### Content Capture and Redaction

Automatic instrumentation is metadata-only by default. Enable input and output independently, and keep a finite post-redaction length limit:

```yaml
langfuse:
  content:
    capture-input: true
    capture-output: true
    max-length: 8192
```

To redact content before export, provide one thread-safe bean:

```java
@Bean
ContentRedactor contentRedactor() {
    return (type, content) -> content.replaceAll("(?i)api[-_ ]?key\\s*[:=]\\s*\\S+", "[REDACTED]");
}
```

No redactor is installed automatically. If capture is enabled without one, values are exported
unchanged except for the length limit. Use one reviewed, thread-safe redactor in production;
ambiguous or failing redactors drop the affected automatic content.

Exception details use a separate safe-by-default policy. Only the exception type is recorded unless message or stack capture is enabled:

```yaml
langfuse:
  exception:
    capture-message: false
    capture-stack-trace: false
    max-length: 8192
```

Exception detail capture follows the same rule with `ExceptionRedactor`. See
[SECURITY.md](SECURITY.md) for the full data boundary and fail-closed behavior.

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

### Core tracing API

The core API supports callbacks, try-with-resources, and explicit `end()`. All three are
synchronous scope APIs: close a handle on the thread that created it and do not pass it into an
asynchronous callback.

### Operational Signals

With the default fail-safe setting, missing API keys or an invalid standalone configuration fall
back to no-op mode without crashing the application. `LangfuseOtel.getStatus()` returns an
immutable snapshot of that fallback, ownership, export, queue-drop, and flush state. Reading it
does not flush or make a network request.

When the application includes `spring-boot-starter-actuator`, the starter registers a `langfuse` health component and Micrometer meters. The Actuator dependency remains optional and is not added transitively by this library.

- `UP`: the standalone pipeline is owned and has no current failure. This can include the initial state before the first export.
- `DOWN`: the latest export failed, a queue drop has not yet been followed by a successful export, or the latest flush failed or timed out.
- `OUT_OF_SERVICE`: fail-safe construction produced the library's no-op fallback.
- `UNKNOWN`: OpenTelemetry is application-owned, so its exporter, queue, and flush state are not observed here.

Owned pipelines publish fallback, export-failure, queue-drop, and flush meters. External mode omits
transport meters because this library does not own that pipeline. A successful flush confirms a
local SDK drain, not remote ingestion.

Keep this component out of liveness. Add it to readiness only when losing Langfuse delivery should intentionally stop the application from receiving traffic.

---

## Modules

| Module | Java | Description |
|--------|------|-------------|
| `langfuse-otel-core` | 11+ | Core tracing library — no framework dependency |
| `langfuse-otel-spring-boot-starter` | 17+ | Auto-config for Spring AI & LangChain4j |

## Configuration (Spring Boot)

| Property | Default | Description |
|----------|---------|-------------|
| `langfuse.public-key` | — | Standalone mode public key; not used with an external OTel bean |
| `langfuse.secret-key` | — | Standalone mode secret key; not used with an external OTel bean |
| `langfuse.host` | `https://cloud.langfuse.com` | Standalone mode Langfuse host URL |
| `langfuse.allow-insecure-http-for-development` | `false` | Allow a plaintext standalone HTTP endpoint on `localhost` or a literal loopback address only |
| `langfuse.service-name` | `langfuse-app` | Standalone mode service name |
| `langfuse.environment` | — | Standalone resource environment (e.g., `production`) |
| `langfuse.release` | — | Standalone resource release version |
| `langfuse.enabled` | `true` | Enable/disable all tracing |
| `langfuse.otel-mode` | `auto` | `auto`, `external`, or `standalone` OpenTelemetry ownership selection |
| `langfuse.content.capture-input` | `false` | Capture automatic model input |
| `langfuse.content.capture-output` | `false` | Capture automatic model output |
| `langfuse.content.max-length` | `8192` | Maximum UTF-16 units retained after redaction |
| `langfuse.exception.capture-message` | `false` | Capture policy-processed exception messages from automatic instrumentation |
| `langfuse.exception.capture-stack-trace` | `false` | Capture policy-processed exception stack traces from automatic instrumentation |
| `langfuse.exception.max-length` | `8192` | Maximum UTF-16 units retained per exception detail after redaction |
| `langfuse.context.capture-user-id` | `false` | Export the authenticated Principal name as `user.id` |
| `langfuse.context.capture-session-id` | `false` | Export the HTTP session identifier as `session.id`; avoid this for bearer-style session IDs |

## Production-preview limitations

- Generic instrumentation cannot enter opaque provider-owned executors. Integrations that own a
  raw source or scheduling boundary must use the supplied context adapters.
- Final or otherwise non-proxyable model types are left unchanged and logged as uninstrumented.
- `0.2.x` is JVM-only; Spring AOT and GraalVM native-image support are not claimed.
- Custom concrete publisher subtypes are returned unchanged when a compatible wrapper type cannot
  be preserved.

Remaining production work is tracked in [ROADMAP.md](ROADMAP.md); `0.2.0-SNAPSHOT` is not the final production release.

## Compatibility

| Dependency | Tested Version | Notes |
|-----------|---------------|-------|
| Java | 11+ | Core module |
| Java | 17+ | Spring Boot starter |
| OpenTelemetry SDK | 1.62.0 | Via BOM |
| Spring Boot | 3.5.16 | Boot 3 auto-configuration |
| Spring AI | 1.0.9 / 1.1.8 | Chat, streaming, embeddings, images |
| LangChain4j | 1.0.0 / 1.18.0 | Chat, streaming, embeddings, images; provider internals vary |
| langfuse-java | 0.2.0 | Prompt management (optional) |
| Langfuse Cloud | v3+ | OTLP ingestion |
| Langfuse Self-hosted | v3.22.0+ | Requires OTLP support |

`0.2.x` is the Spring Boot 3 and Spring AI 1 line. `0.3.x` will move the same starter
coordinates to Spring Boot 4 and Spring AI 2; the core module remains framework-neutral. See
[SECURITY.md](SECURITY.md#supported-versions) for the maintenance window.

## Examples

See the [examples](./examples) directory:
- [Spring AI + OpenAI](./examples/spring-ai-example) — zero-code tracing
- [LangChain4j + OpenAI](./examples/langchain4j-example) — zero-code tracing

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

## License

MIT
