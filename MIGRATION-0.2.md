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

## Use the v4 OTLP contract

Standalone mode sends `x-langfuse-ingestion-version: 4` automatically. External SDKs and
Collectors must add that header to their trace exporter for real-time v4 ingestion and
Observations API v2 read-back.

Trace roots and generic steps now identify as `span`, chat/image and annotated calls as
`generation`, and embeddings as `embedding`. Root input/output use
`langfuse.observation.input/output` and are also written to the legacy
`langfuse.trace.input/output` attributes during migration.

Trace name, user/session IDs, tags, metadata, version, release, and environment are copied when
library-created or owned-pipeline descendants start. Set them before creating children when every
observation must agree; prior spans are not backfilled. A supplied external SDK cannot be modified
after construction, so raw application or third-party spans need application-owned propagation.

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
  for that bean. Both paths require a call through the Spring proxy; direct construction,
  self-invocation, and private/final methods are outside the interception boundary.
- Generic instrumentation cannot enter opaque provider-owned executors. Use
  `ReactorContextPropagation` or `LangChain4jStreamingContext` at boundaries owned by the
  integration. See [DESIGN.md](DESIGN.md#asynchronous-observation-and-context-lifecycle).
- Active Reactor observations install a keyed process-global scheduler hook until their final
  lease ends. Async spans have no internal timeout, so providers must deliver a terminal callback
  or applications must enforce timeout/cancellation.
- Standalone hosts require HTTPS. The development-only plaintext option accepts loopback hosts
  only.

Legacy `LangfuseContext.set*()` and `clear()` mutations are scoped by
`LangfuseContext.makeCurrent(...)`. An immutable context installed directly with `storeIn(...)`
ignores those legacy mutations so they cannot escape through a reused executor thread.
Inside an active `LangfuseTrace`, its trace-local carrier takes precedence over nested immutable
contexts; update that trace through its fluent setters.

## Upgrade checklist

- Run `./mvnw -B -ntp clean verify` and compile application consumers against `0.2.0`.
- Choose `langfuse.otel-mode` and verify that the selected pipeline reaches Langfuse.
- Review content, exception, principal, and session capture before enabling each field.
- Exercise streaming completion, error, cancellation, and re-subscription.
- Verify root I/O, hierarchy, observation types, and trace-wide fields through Langfuse
  Observations API v2.
- Confirm provider-specific concrete injection still resolves and investigate any non-proxyable
  model warning.
- Add alerts for fail-safe fallback, export failures, queue drops, and flush failures.
