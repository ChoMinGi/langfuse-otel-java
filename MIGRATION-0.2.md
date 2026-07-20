# Migrating from 0.1.x to 0.2

0.2 changes automatic instrumentation defaults to make production data boundaries and OpenTelemetry ownership explicit. Review these items before upgrading.

## 1. Model content is no longer captured automatically

Spring AI, LangChain4j, streaming, embedding, image, and `@ObserveGeneration` wrappers now omit input and output unless enabled independently:

```yaml
langfuse:
  content:
    capture-input: true
    capture-output: true
    max-length: 8192
```

Capture remains usable without a `ContentRedactor`, but zero beans means identity behavior: enabled content is exported unchanged except for the configured length limit. In production, provide exactly one reviewed, thread-safe redactor before enabling potentially sensitive capture. Multiple beans, redactor failures, or a `null` result fail closed and drop the affected content. Explicit core fluent calls to `.input(...)` and `.output(...)` remain intentional direct capture.

## 2. Exception message and stack trace are opt-in

Automatic instrumentation and direct wrapper `.recordException(...)` calls retain the exception type but omit message and stack trace by default. To enable details for automatic instrumentation:

```yaml
langfuse:
  exception:
    capture-message: true
    capture-stack-trace: false
    max-length: 8192
```

With zero `ExceptionRedactor` beans, enabled details use identity behavior and are exported unchanged except for the configured length limit. In production, provide exactly one reviewed, thread-safe redactor before enabling potentially sensitive details. Multiple beans, redactor failures, or a `null` result fail closed. Code using the core API can configure `ExceptionCapturePolicy` and call `LangfuseOtel.recordException(...)`.

## 3. Principal and HTTP session export are opt-in

The starter no longer exports authenticated principal names or raw HTTP session IDs by default:

```yaml
langfuse:
  context:
    capture-user-id: true
    capture-session-id: false
```

Treat session IDs as credentials unless the application has explicitly defined a non-secret analytics identifier.

## 4. Choose OpenTelemetry ownership deliberately

The default `langfuse.otel-mode=auto` reuses one unambiguous application `OpenTelemetry` bean. In that mode, `public-key`, `secret-key`, `host`, and `service-name` are ignored; the application SDK or Collector must already export OTLP traces to Langfuse.

Use one of these settings when auto-selection is not the intended behavior:

```yaml
langfuse:
  otel-mode: external   # require exactly one application OpenTelemetry bean
  # otel-mode: standalone  # create and own a dedicated Langfuse SDK/exporter
```

Without Spring, `LangfuseOtel.externalBuilder(openTelemetry)` is non-owning. Its `flush()` and `close()` methods do not affect the supplied SDK.

Standalone hosts now require HTTPS and reject user-info, query, fragment, missing-host, and non-HTTP(S) URIs. A plaintext receiver requires the explicit development-only builder option `.allowInsecureHttpForDevelopment(true)` or Spring property `langfuse.allow-insecure-http-for-development=true`, and its host must still be `localhost` or a literal loopback address.

Legacy `LangfuseContext.set*()` and `clear()` mutations can override immutable metadata only within `LangfuseContext.makeCurrent(...)`, which restores them on close. A scope created directly from `LangfuseContext.storeIn(...)` remains immutable and ignores legacy mutations, preventing executor-thread state from escaping the scope.

## 5. Streaming lifecycle is subscription-scoped

Spring AI streaming creates a separate span and bounded output accumulator for every subscription. Merely creating a `Flux` no longer creates a span, and re-subscribing no longer reuses prior trace state. Verify any code that relied on eager instrumentation side effects.

When output capture is enabled, a raw stream that exceeds `content.max-length` is now omitted entirely. Shorter streams are redacted at completion and then length-limited. This fail-closed behavior avoids exporting a pre-redaction prefix when a sensitive pattern crosses the buffer boundary.

## 6. Validate adapter compatibility

0.2 remains a production preview. Automatic instrumentation now uses a class-based proxy for safely proxyable models, preserving provider concrete types and extension interfaces. Final provider classes, final model methods, and externally callable final extension methods cannot be safely intercepted or delegated without changing behavior, so they remain unchanged and emit an instrumentation warning. Existing JDK proxies retain only the interfaces already present on that proxy. When singleton circular references are explicitly enabled, the BeanPostProcessor promotes one instrumented early proxy as the final singleton so injected early and final references retain identity.

Streaming-only LangChain4j beans are no longer synchronous `ChatModel` candidates in the automatic BeanPostProcessor path. The legacy manual `TracingStreamingLangChain4jChatModel` keeps both interfaces for binary compatibility. `@ObserveGeneration` now tracks `CompletionStage` completion and Reactor terminal signals; future identity is preserved and Reactor spans are created per subscription. Reactor Scheduler work scheduled under the active instrumented subscription and final raw-thread downstream signals are restored automatically. When provider-side operators sit above a raw-thread source, wrap that source with `ReactorContextPropagation.wrap(...)`. For LangChain4j provider scheduling, use `LangChain4jStreamingContext.wrap(...)`/`taskWrapping(...)` at a submission point you control or retain a per-invocation `Snapshot` for later submission. A shared model executor is not request-scoped; opaque provider work requires a provider-owned scheduling hook or agent instrumentation.

An explicit `@ObserveGeneration` on any Spring AI or LangChain4j model method now takes precedence for the entire model bean. Automatic BeanPostProcessor instrumentation is skipped for that bean so the two mechanisms do not produce duplicate spans; annotate every model entry point that should remain traced. Keep model annotations and automatic model instrumentation as alternative bean-level strategies; annotations on ordinary service beans continue to work independently.

## Upgrade checklist

- Run `./mvnw -B -ntp clean verify` and compile application consumers against `0.2.0-SNAPSHOT`.
- Decide `langfuse.otel-mode` and verify the selected exporter actually reaches Langfuse.
- Approve each content, exception, principal, and session field before enabling it.
- Exercise streaming complete, error, and cancellation paths under load.
- Confirm provider-specific concrete injection and extension APIs resolve through the proxied bean, and alert on any non-proxyable-model warning.
- Exercise annotated future and reactive methods through success, failure, cancellation, and re-subscription.
