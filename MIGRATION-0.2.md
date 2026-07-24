# Migrating from 0.1.x to 0.2

Version 0.2 makes data capture and OpenTelemetry ownership explicit. Review the changes below
before upgrading.

## Model content is opt-in

Automatic Spring AI, LangChain4j, embedding, image, streaming, and `@ObserveGeneration` tracing no
longer records input or output unless each direction is enabled:

```yaml
langfuse:
  content:
    capture-input: true
    capture-output: true
    max-length: 8192
```

Enabling capture without a `ContentRedactor` exports the selected values unchanged up to the
configured limit. Production applications should install one reviewed, thread-safe redactor before
capturing sensitive content. See [SECURITY.md](SECURITY.md) for fail-closed behavior and streaming
limits. Explicit core `.input(...)` and `.output(...)` calls remain direct capture.

## Exception details are opt-in

Automatic instrumentation and direct wrapper `.recordException(...)` calls keep the exception type
but omit message and stack trace by default:

```yaml
langfuse:
  exception:
    capture-message: true
    capture-stack-trace: false
    max-length: 8192
```

Use one reviewed `ExceptionRedactor` before enabling sensitive details. Code using the core API can
configure `ExceptionCapturePolicy` and call `LangfuseOtel.recordException(...)`.

## Principal and session export are opt-in

```yaml
langfuse:
  context:
    capture-user-id: true
    capture-session-id: false
```

The user value comes from `Principal#getName()`. Treat HTTP session IDs as credentials unless the
application has defined them as non-secret analytics identifiers.

## Choose OpenTelemetry ownership

The default `langfuse.otel-mode=auto` reuses one unambiguous application `OpenTelemetry` bean. Its
export pipeline must already route traces to Langfuse; standalone keys and host settings are not
used in this mode.

```yaml
langfuse:
  otel-mode: external     # require an unambiguously selectable application OpenTelemetry bean
  # otel-mode: standalone # own a dedicated Langfuse SDK and exporter
```

Without Spring, `LangfuseOtel.externalBuilder(openTelemetry)` is non-owning, so `flush()` and
`close()` do not affect the supplied SDK.

## Operational and adapter changes

- Rely on starter auto-discovery instead of explicitly importing or excluding
  `LangfuseOtelAutoConfiguration`. Use `langfuse.enabled=false` to disable the integration. Version
  0.2 separates core and web auto-configuration so servlet and WebFlux dependencies remain optional.
- Applications with Actuator receive a `langfuse` health component and meters. Keep the component
  out of liveness; include it in readiness only if trace-delivery failure should stop traffic.
- Spring AI streams create state per subscription. An unsubscribed `Flux` creates no span, and
  each re-subscription creates a new one.
- Automatic model instrumentation preserves safely proxyable concrete types. Final or otherwise
  non-proxyable model types remain unchanged and log a warning.
- A model-method `@ObserveGeneration` annotation takes precedence over automatic instrumentation
  for that bean.
- Generic instrumentation cannot enter opaque provider-owned executors. Use
  `ReactorContextPropagation` or `LangChain4jStreamingContext` at boundaries owned by the
  integration. See [DESIGN.md](DESIGN.md#asynchronous-observation-and-context-lifecycle).
- Standalone hosts require HTTPS. The development-only plaintext option accepts loopback hosts
  only.

Legacy `LangfuseContext.set*()` and `clear()` mutations are scoped by
`LangfuseContext.makeCurrent(...)`. An immutable context installed directly with `storeIn(...)`
ignores those legacy mutations so they cannot escape through a reused executor thread.

## Upgrade checklist

- Run `./mvnw -B -ntp clean verify` and compile application consumers against `0.2.0`.
- Choose `langfuse.otel-mode` and verify that the selected pipeline reaches Langfuse.
- Review content, exception, principal, and session capture before enabling each field.
- Exercise streaming completion, error, cancellation, and re-subscription.
- Confirm provider-specific concrete injection still resolves and investigate any non-proxyable
  model warning.
- Add alerts for fail-safe fallback, export failures, queue drops, and flush failures.
