<div align="center">

# langfuse-otel-java

**LLM observability for Java — zero config, one dependency.**

[![CI](https://github.com/ChoMinGi/langfuse-otel-java/actions/workflows/ci.yml/badge.svg)](https://github.com/ChoMinGi/langfuse-otel-java/actions)
[![Java Core 11%2B / Starter 17%2B](https://img.shields.io/badge/Java-core%2011%2B%20%7C%20starter%2017%2B-blue)](https://openjdk.org/)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-enabled-blueviolet)](https://opentelemetry.io/)

[Why this exists](#the-problem) · [Quick Start](#quick-start) · [What gets traced](#what-gets-traced) · [Features](#features) · [Compatibility](#compatibility) · [Migration](MIGRATION-0.2.md) · [Roadmap](ROADMAP.md) · [Release process](RELEASING.md)

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

For a dedicated exporter, add one dependency, select standalone mode, and configure the connection properties. Supported Spring AI and LangChain4j calls — sync, streaming, embeddings, and image generation — are then exported to Langfuse.

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

Reactor scheduler transitions and downstream signals are bridged automatically. If a provider
owns a raw Reactive Streams source that emits from a plain thread, place the explicit boundary at
the closest raw source so provider-side operators also run with the subscription context:

```java
Publisher<ChatResponse> rawPublisher = provider.openStream(prompt);

return Flux.from(ReactorContextPropagation.wrap(rawPublisher))
        .map(this::providerSideMapping);
```

`ReactorContextPropagation.wrap(...)` resolves each subscription independently and scopes source
subscription, signals, request, and cancel. The automatic model/annotation wrapper still restores
the final downstream signal when this explicit boundary is absent, but it cannot reach provider
`map`/`filter` operators already assembled upstream of that final wrapper. Code executed by the raw
source before it calls a Reactive Streams boundary also remains provider-owned.

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

Streaming callbacks and model listeners run with the wrapper observation and immutable Langfuse request metadata restored automatically. This is the guaranteed boundary for stock providers.

The LangChain4j OpenAI JDK transport was exercised at 1.0.0 and 1.18.0 with a configured `HttpClient` executor. That hook controls HTTP-client work. Its SSE continuation is not guaranteed to remain on the supplied executor, so wrapping a shared model executor is not an end-to-end propagation guarantee. Keep a configured executor application-owned and shut it down with the application; never bind a request-specific `Snapshot` to a shared model executor.

When you implement a provider adapter and control its submission point, use the matching submission-time adapter:

```java
executor.execute(LangChain4jStreamingContext.wrap(() -> providerCall(handler)));
Executor contextAware = LangChain4jStreamingContext.taskWrapping(executor);
ScheduledExecutorService contextAwareScheduler =
        LangChain4jStreamingContext.taskWrapping(scheduler);
```

These adapters capture the context at submission, so they cover work submitted from `doChat` or a restored callback/listener. If an integration you own retains a request and submits it later from an unscoped control thread, capture one fixed snapshot while `doChat` is active and retain it with that request:

```java
LangChain4jStreamingContext.Snapshot invocation =
        LangChain4jStreamingContext.capture();

// This may be called after doChat has returned and from another thread.
ScheduledExecutorService invocationScheduler =
        invocation.taskWrapping(scheduler);
invocationScheduler.schedule(() -> providerCall(handler), 10, TimeUnit.MILLISECONDS);
```

A snapshot is thread-safe and opens only short-lived scopes while a task executes. It is also terminal-aware: a task whose wrapper execution is admitted after terminal cleanup still runs, but without restoring the ended observation. A task admitted before cleanup is already in flight and finishes with its captured context; terminal completion does not block on arbitrary provider work. Keep one snapshot per invocation and release it with the provider request state after the terminal callback; the library keeps no global request registry.

A provider-owned executor that exposes neither configuration nor a scheduling hook cannot be intercepted through the generic LangChain4j SPI. Such a provider requires its own executor configuration/adapter or OpenTelemetry agent instrumentation; callbacks remain covered automatically.

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

`@ObserveGeneration` tracks synchronous methods, the actual completion of `CompletionStage` values, and Reactor `Mono`/`Flux` terminal signals. A `CompletionStage` is returned unchanged, including its concrete type and identity. Reactor observations are created per subscription, so an unsubscribed publisher creates no span and re-subscription creates an independent span. Tasks scheduled through Reactor while the instrumented subscription context is current inherit the observation and request metadata until that subscription terminates. When output capture is enabled for a multi-value publisher, the last emitted value is used as the automatic output.

Explicit annotation wins at the model-bean boundary: if a Spring AI or LangChain4j model bean has `@ObserveGeneration` on any model method, BeanPostProcessor-based model instrumentation is skipped for that whole bean to prevent duplicate generation spans. Use one instrumentation style per model bean and annotate every model entry point that needs tracing; annotations on ordinary service methods are unaffected.

When circular references are explicitly enabled, the model post-processors participate in Spring's early-reference phase so injected collaborators and the final singleton receive the same instrumented proxy. Spring Boot still disables circular references by default.

### Request Context Propagation

```java
// Set once in a filter or interceptor
LangfuseContext.setUserId("user-123");
LangfuseContext.setSessionId("session-456");
LangfuseContext.setTags("prod", "v2");

// Synchronous traces on this thread inherit these values
langfuse.trace("flow", trace -> { ... });

// Spring Boot filters can extract HTTP metadata after explicit opt-in
// Servlet: Principal → userId, HttpSession → sessionId
// WebFlux: stores the opted-in immutable metadata in Reactor Context
```

New integrations can use immutable metadata with OTel Context directly:

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

`LangfuseContext.makeCurrent(...)` is also the restoration boundary for legacy `set*()` and `clear()` calls made inside that scope. A context installed directly with `storeIn(...).makeCurrent()` remains immutable, so legacy mutations inside such an unmanaged scope are ignored instead of leaking into a reused executor thread.

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

Capture opt-in does not implicitly install a redactor. With zero `ContentRedactor` beans, the identity redactor is used and enabled content is exported unchanged except for the length limit. Production environments that may capture sensitive values should treat exactly one thread-safe redactor as required. Multiple beans fail closed and drop automatic content; redactor failures or a `null` result also drop the affected value.

Non-streaming values are redacted before the length limit is applied. Spring AI streaming retains only a bounded raw value; if the raw stream exceeds `max-length` before terminal redaction, the output attribute is dropped entirely instead of exporting a prefix that could bypass a boundary-sensitive redactor.

The policy applies to automatic Spring AI, LangChain4j, streaming, embedding, image, and `@ObserveGeneration` capture. Explicit calls to the core fluent `.input()` and `.output()` methods remain an intentional opt-in by the caller.

Exception details use a separate safe-by-default policy. Only the exception type is recorded unless message or stack capture is enabled:

```yaml
langfuse:
  exception:
    capture-message: false
    capture-stack-trace: false
    max-length: 8192
```

Exception detail opt-in also uses an identity redactor when no `ExceptionRedactor` bean exists. In production, provide exactly one thread-safe redactor before enabling potentially sensitive messages or stacks. Multiple beans, redactor failures, or a `null` result fail closed and drop the affected details. Automatic wrappers and `LangfuseOtel.recordException(...)` use this policy; the direct span wrapper `.recordException(...)` remains type-only.

Stack capture renders exception types and frames without embedding throwable messages, including messages from causes and suppressed exceptions. Enable `capture-message` separately when the redacted message is required.

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

All three wrapper styles are synchronous scope APIs. Close each handle on the thread that created
it, in reverse creation order. Do not pass a handle to a `CompletionStage` or reactive callback;
use the starter's async instrumentation or a raw OpenTelemetry span with short-lived scopes at
the callback boundaries.

### Operational Signals

Missing API keys or an invalid standalone configuration still fall back to no-op mode without crashing the application. `LangfuseOtel.getStatus()` returns an immutable snapshot of that fallback, ownership, export, queue-drop, and flush state. Reading it does not flush or make a network request.

When the application includes `spring-boot-starter-actuator`, the starter registers a `langfuse` health component and Micrometer meters. The Actuator dependency remains optional and is not added transitively by this library.

- `UP`: the standalone pipeline is owned and has no current failure. This can include the initial state before the first export.
- `DOWN`: the latest export failed, a queue drop has not yet been followed by a successful export, or the latest flush failed or timed out.
- `OUT_OF_SERVICE`: fail-safe construction produced the library's no-op fallback.
- `UNKNOWN`: OpenTelemetry is application-owned, so its exporter, queue, and flush state are not observed here.

A later successful export clears the current exporter/drop health condition but cannot recover spans that were already lost. A successful flush means only that the local SDK drain completed; it does not prove that Langfuse accepted every span.

The starter publishes `langfuse.otel.noop.fallback` with fixed `ownership` and `fallback_reason` tags. Its value is `1` only for this library's fail-safe fallback, not for an externally supplied no-op SDK. Owned pipelines also publish cumulative `langfuse.otel.export.failed.spans`, `langfuse.otel.queue.dropped.spans`, `langfuse.otel.flush.failures`, and `langfuse.otel.flush.timeouts` counters. `langfuse.otel.flush.state` is a one-hot gauge with a fixed `state` tag.

External and no-op modes omit transport meters rather than reporting unobserved work as zero. `langfuse.enabled=false` removes both health and meter beans. The standard `management.health.langfuse.enabled=false` and `management.metrics.enable.langfuse=false` properties disable either surface independently.

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

- WebFlux request metadata and the wrapper OpenTelemetry context propagate through Spring AI streams and `@ObserveGeneration` Reactor publishers, including raw downstream signals and Reactor Scheduler tasks scheduled while that instrumented subscription context is current. The keyed hook is removed after the last subscription lease closes, and a task whose execution starts after termination does not restore the ended context. Provider-side operators above a raw-thread source require `ReactorContextPropagation.wrap(rawPublisher)` at that source; arbitrary work performed before the source invokes `subscribe`, a signal, `request`, or `cancel` cannot be intercepted by a Reactive Streams adapter.
- LangChain4j streaming callbacks and model listeners restore the invocation context automatically. Provider-internal work can inherit it only at a scheduling boundary the integration controls with `LangChain4jStreamingContext.wrap(...)`, `taskWrapping(...)`, or a per-invocation `Snapshot`, or through provider-specific/agent instrumentation. A model-wide executor setting is not itself request-scoped, and an opaque executor cannot be reached through the generic model SPI.
- The 0.2 compatibility matrix is JVM-only. The reflective LangChain4j 1.18 cancellation bridge is not validated for Spring AOT or GraalVM native images and fails open when its runtime types cannot be reflected; native-image support requires dedicated runtime hints and tests before it can be claimed.
- Automatic model instrumentation uses a class-based proxy for safely proxyable provider classes, preserving their concrete type and extension interfaces. Final classes, final model methods, or externally callable final extension methods are left unchanged and logged as uninstrumented rather than replaced with an incompatible interface decorator.
- Existing JDK proxies retain their declared interfaces; they cannot recover a concrete type that was already removed by the original proxy.
- Completion-aware annotation support covers `CompletionStage` and declared return types compatible with Reactor `Mono`, `Flux`, or `Publisher`. A custom concrete publisher subtype is returned unchanged and is not automatically tracked when a compatible wrapper type cannot be preserved.
- The legacy manual `TracingStreamingLangChain4jChatModel` keeps both synchronous and streaming interfaces for binary compatibility; the automatic BeanPostProcessor path preserves a streaming-only bean's original interface set.

Remaining production work is tracked in [ROADMAP.md](ROADMAP.md); `0.2.0-SNAPSHOT` is not the final production release.

## Compatibility

| Dependency | Tested Version | Notes |
|-----------|---------------|-------|
| Java | 11+ | Core module |
| Java | 17+ | Spring Boot starter |
| OpenTelemetry SDK | 1.44.1 | Via BOM |
| Spring Boot | 3.4.x | Auto-configuration |
| Spring AI | 1.0.0 — 1.1.8 | Chat, streaming, embeddings, images |
| Spring AI | 2.0.0 | Current stable (CI tested) |
| LangChain4j | 1.0.0 — 1.18.0 | Chat, streaming, embeddings, images; provider internals vary |
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
