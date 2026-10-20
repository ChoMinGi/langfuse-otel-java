# Spring Boot guide

The starter traces supported Spring AI and LangChain4j model beans. Start with the
[dependency and configuration](../README.md#spring-boot-dedicated-exporter-quick-start),
then use this guide to choose an exporter, control captured data, or add request metadata.

Keep your framework, provider, and web server dependencies in the application. The starter
does not select a model provider or install an MVC/WebFlux server.

## Choose the OpenTelemetry SDK

Set `langfuse.otel-mode` according to who owns trace export:

| Mode | Behavior |
| --- | --- |
| `auto` (default) | Reuse an application `OpenTelemetry` bean, or create a dedicated SDK if none exists. |
| `external` | Require an application `OpenTelemetry` bean. |
| `standalone` | Create and own a dedicated Langfuse SDK and exporter. |

For `auto` and `external`, Spring must be able to select one bean: a single candidate or one marked
`@Primary`. Ambiguous candidates fail at startup. `external` also fails when no candidate exists.

With an external SDK, your application owns export, flush, and shutdown. Configure that SDK or
its Collector to send traces to Langfuse. The starter warns if Langfuse keys are configured because
it does not use them in external mode.

For an OTLP/HTTP exporter configured through standard environment variables:

```bash
export AUTH_STRING="$(printf '%s' "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" | base64 | tr -d '\n')"
export OTEL_EXPORTER_OTLP_ENDPOINT="https://cloud.langfuse.com/api/public/otel"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic ${AUTH_STRING},x-langfuse-ingestion-version=4"
```

Configure equivalent endpoint and headers when building the SDK or Collector directly. The
ingestion-version header enables real-time v4 ingestion; without it, directly ingested traces can
be delayed. See Langfuse's [OpenTelemetry guide](https://langfuse.com/integrations/native/opentelemetry)
for other regions, self-hosting, and signal-specific settings.

In plain Java, use `externalBuilder`:

```java
try (LangfuseOtel langfuse = LangfuseOtel.externalBuilder(openTelemetry).build()) {
    langfuse.trace("my-flow", trace -> { /* ... */ });
}
// openTelemetry remains owned by the application
```

Use `LangfuseOtel.builder()` when you want the library to own a dedicated SDK and exporter.

The library applies Langfuse context directly to spans it creates. It cannot add a processor to an
already-built external SDK. To copy the same metadata onto third-party spans, set the attributes
yourself or install a span processor when building your SDK.

## Local Docker

The Docker checks in this repository put a TLS proxy in front of Langfuse. Use that setup
or an HTTPS-capable collector for local testing.

`langfuse.allow-insecure-http-for-development=true` allows a plaintext endpoint on
`localhost` or a literal loopback address. It changes endpoint validation only; it does
not select the HTTP protocol used by the exporter. A previous direct-Docker failure is
recorded in [#9](https://github.com/ChoMinGi/langfuse-otel-java/issues/9). That issue is closed,
but the current read-back checks use TLS and do not verify direct plaintext Docker export.
Use disposable credentials for local testing.

## What gets traced

Calls must pass through a Spring-managed proxy implementing one of these model interfaces.
Objects created with `new`, direct provider-SDK calls, and calls that bypass the proxy are not
automatically traced.

### Spring AI

| Model | Methods | Operation |
|-------|---------|-----------|
| `ChatModel` | `call(Prompt)` | `chat` |
| `ChatModel` | `stream(Prompt)` | `chat` (with TTFT) |
| `EmbeddingModel` | `call(EmbeddingRequest)`, document and bulk `embed(...)` | `embeddings` |
| `ImageModel` | `call(ImagePrompt)` | `image_generation` |

Reactor scheduler transitions and downstream signals are bridged automatically. Provider adapters
that own a raw, plain-thread source can place
`ReactorContextPropagation.wrap(rawPublisher)` at that source boundary. See the
[async lifecycle design](../DESIGN.md#asynchronous-observation-and-context-lifecycle) for the exact
boundary.

### LangChain4j

| Model | Methods | Operation |
|-------|---------|-----------|
| `ChatModel` | `chat(ChatRequest)` | `chat` |
| `StreamingChatModel` | `chat(ChatRequest, Handler)` | `chat` (with TTFT) |
| `EmbeddingModel` | `embedAll(...)`, `embed(...)` | `embeddings` |
| `ImageModel` | `generate(...)`, `edit(...)` | `image_generation` |

Streaming callbacks and model listeners restore the wrapper observation and immutable request
metadata. Provider adapters that control task submission can use
`LangChain4jStreamingContext.wrap(...)`, `taskWrapping(...)`, or a per-request `Snapshot`.
Opaque provider executors remain outside the generic SPI; the
[async lifecycle design](../DESIGN.md#asynchronous-observation-and-context-lifecycle) describes that
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

## @ObserveGeneration

Add the annotation to a Spring service method:

```java
@Service
public class LLMService {
    @ObserveGeneration(name = "summarize", model = "gpt-4o", system = "openai")
    public String summarize(String text) {
        return callLLM(text);
    }
}
```

The annotation supports synchronous methods, `CompletionStage`, and Reactor `Mono`/`Flux`.
It returns the original stage; Reactor creates one observation per subscription. On a model bean,
the annotation takes precedence over automatic instrumentation.

Call the method through its Spring proxy. Self-invocation and private methods are not advised.
CGLIB cannot advise final methods or final classes. Since 0.2.1, JDK proxies resolve
implementation-method annotations, including final implementations and generic bridge methods.
For that path, use `spring.aop.proxy-target-class=false` and inject the interface.
Ordering with other advice, such as `@Transactional`, is not guaranteed.

## Request context

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

Trace name, user/session IDs, tags, metadata, version, release, and environment are copied when a
library-created descendant starts. Set fluent trace-wide values before creating children when all
observations must agree; already-started spans are not backfilled. While a `LangfuseTrace` is
active, its trace-local carrier is authoritative, so nested `storeIn(...)` or `makeCurrent(...)`
metadata does not replace that trace. Use the trace fluent setters for intentional updates.

## Input, output, and exceptions

Input and output capture are off by default. Enable either direction and set a length limit:

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

This regex is an example; it does not cover every form of sensitive data. Supply one thread-safe
redactor for the data your application handles. Without a redactor bean, enabled content is sent
unchanged up to the length limit. Multiple beans, a redactor failure, or a null result drop the
affected content.

Only the exception type is recorded by default. Message and stack capture have separate settings:

```yaml
langfuse:
  exception:
    capture-message: false
    capture-stack-trace: false
    max-length: 8192
```

Exception detail capture follows the same rule with `ExceptionRedactor`. See
[SECURITY.md](../SECURITY.md) for the full data boundary and fail-closed behavior.

## Health and metrics

If keys are missing or standalone setup fails, the default fail-safe mode keeps the application
running without sending traces. Check `LangfuseOtel.getStatus()` for fallback, ownership, export,
queue-drop, and flush state. Reading status makes no network request.

Add `spring-boot-starter-actuator` to expose a `langfuse` health component and Micrometer meters:

- `UP`: the standalone pipeline is owned and has no current failure. This can include the initial state before the first export.
- `DOWN`: the latest export failed, a queue drop has not yet been followed by a successful export, or the latest flush failed or timed out.
- `OUT_OF_SERVICE`: fail-safe construction produced the library's no-op fallback.
- `UNKNOWN`: OpenTelemetry is application-owned, so its exporter, queue, and flush state are not observed here.

Owned pipelines publish fallback, export-failure, queue-drop, and flush meters. External mode omits
transport meters because this library does not own that pipeline. A successful flush confirms a
local SDK drain, not remote ingestion.

Keep this component out of liveness. Include it in readiness only if trace-delivery failure should
stop the application from receiving traffic.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `langfuse.public-key` | — | Standalone mode public key; not used with an external OTel bean |
| `langfuse.secret-key` | — | Standalone mode secret key; not used with an external OTel bean |
| `langfuse.host` | `https://cloud.langfuse.com` | Standalone mode Langfuse host URL |
| `langfuse.allow-insecure-http-for-development` | `false` | Allow a plaintext standalone HTTP endpoint on `localhost` or a literal loopback address only |
| `langfuse.service-name` | `langfuse-app` | Standalone mode service name |
| `langfuse.environment` | — | Standalone resource environment (e.g., `production`) |
| `langfuse.release` | — | Standalone resource release version |
| `langfuse.enabled` | `true` | Enable/disable starter auto-configuration |
| `langfuse.otel-mode` | `auto` | `auto`, `external`, or `standalone` OpenTelemetry ownership selection |
| `langfuse.content.capture-input` | `false` | Capture automatic model input |
| `langfuse.content.capture-output` | `false` | Capture automatic model output |
| `langfuse.content.max-length` | `8192` | Maximum UTF-16 units retained after redaction |
| `langfuse.exception.capture-message` | `false` | Capture policy-processed exception messages from automatic instrumentation |
| `langfuse.exception.capture-stack-trace` | `false` | Capture policy-processed exception stack traces from automatic instrumentation |
| `langfuse.exception.max-length` | `8192` | Maximum UTF-16 units retained per exception detail after redaction |
| `langfuse.context.capture-user-id` | `false` | Export the authenticated Principal name as `user.id` |
| `langfuse.context.capture-session-id` | `false` | Export the HTTP session identifier as `session.id`; avoid this for bearer-style session IDs |

## Limitations

- Generic instrumentation cannot enter opaque provider-owned executors. Integrations that own a
  raw source or scheduling boundary must use the supplied context adapters.
- During active instrumented Reactor subscriptions, a keyed `Schedulers.onScheduleHook` is
  process-global. Every Reactor scheduled task in the JVM performs a small decorator lookup during
  that window; unrelated tasks receive no Langfuse context, and the hook is removed after the last
  lease ends.
- Async observations are terminal-driven and have no library timeout. A publisher, stage, or
  LangChain4j provider that never completes, errors, or is cancelled can leave its observation
  open; configure provider/application timeouts and cancellation.
- Final or otherwise non-proxyable model types are left unchanged and logged as uninstrumented.
  Annotation-based tracing also cannot advise self-invocation, private methods, or final methods
  through class-based proxies. Existing JDK proxies can advise public interface methods.
- `0.2.x` is JVM-only; Spring AOT and GraalVM native-image support are not claimed.
- Custom concrete publisher subtypes are returned unchanged when a compatible wrapper type cannot
  be preserved.

`0.2.x` remains a production-preview line. Work for later releases is tracked in
[ROADMAP.md](../ROADMAP.md).

## Test coverage

Consumer tests start a non-web Spring Boot 3.5.16 application on Java 17 and
invoke a framework `ChatModel` for both versions in the [compatibility table](../README.md#compatibility).
External mode
verifies the resulting span with an application-owned SDK; standalone mode verifies the OTLP path,
authentication and ingestion headers, service resource, and model attributes against a loopback
receiver. The broader adapter behavior is covered by the Java 17/21 starter test matrix. Provider
clients and other Spring Boot 3 minor releases are not part of that runtime smoke.
