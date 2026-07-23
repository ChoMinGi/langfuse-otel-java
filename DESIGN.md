# Design

This document records the decisions that shape the public API and its runtime behavior. Smaller
implementation details belong in code, tests, and Javadocs.

## OpenTelemetry as the transport

The library creates Langfuse-compatible OpenTelemetry spans instead of calling the ingestion API
directly. This lets an application route the same trace through its existing SDK or Collector.
Langfuse documents this path in its
[OpenTelemetry integration guide](https://langfuse.com/integrations/native/opentelemetry).

## Fail-safe observability

`LangfuseOtel.builder()` defaults to `failSafe(true)`. Missing keys or a standalone setup failure
produce an observable no-op fallback instead of stopping the host application. Callers that want
strict construction can disable fail-safe mode.

This does not hide ambiguous ownership. Spring `external` mode requires one application
`OpenTelemetry` bean, and `auto` mode refuses to guess when multiple candidates are equally valid.

## Synchronous span lifecycle

The core API supports callbacks, try-with-resources, and explicit `end()`. They share one contract:
each wrapper retains an OpenTelemetry `Scope`, so it must close on its creating thread and in
reverse creation order.

An abandoned wrapper registers a `Cleaner` action that warns and ends the span. The cleaner never
closes the originating thread's `Scope`; it is a last-resort span cleanup, not a substitute for a
correct close.

## OpenTelemetry ownership

`LangfuseOtel.builder()` creates and owns a dedicated SDK and exporter.
`LangfuseOtel.externalBuilder(openTelemetry)` uses an application-owned SDK and creates no
exporter. In external mode, export routing, resources, batching, flushing, and shutdown remain the
application's responsibility; the library's `flush()` and `close()` do not affect that SDK.

A process should normally own one OpenTelemetry SDK. Making ownership explicit avoids duplicate
exports, split traces, and shutting down infrastructure owned by the host application.

## Optional prompt client

Prompt management uses the optional `com.langfuse:langfuse-java` dependency. Public entry points
accept the client as `Object` so applications that do not use prompt management can load the core
API without that optional type on their classpath. The trade-off is runtime validation instead of
compile-time type safety for this feature.

## Type-preserving model instrumentation

The starter installs programmatic Spring proxies around supported model beans. Model calls route
through tracing decorators, while provider extension methods continue to target the original
model. A safely proxyable class keeps its concrete type and declared interfaces.

Final classes, final model methods, or externally callable final extension methods cannot be
intercepted without changing behavior. The starter therefore leaves those beans unchanged and
logs the instrumentation gap. Existing JDK proxies retain only the interfaces they already expose.

If a model method declares `@ObserveGeneration`, explicit annotation-based tracing takes
precedence for that entire model bean. Automatic model instrumentation is skipped so one call does
not create nested duplicate observations.

## Privacy-first capture

Automatic instrumentation records metadata but not model input, output, exception messages, or
stack traces by default. Applications opt into each data direction independently and can provide
separate content and exception redactors.

No redactor is installed implicitly. Enabled values with no redactor are exported unchanged up to
their configured limit. Multiple redactors, redactor failures, and `null` redactor results fail
closed for the affected value.

Streaming output is bounded before terminal redaction. A raw completion that crosses the limit is
dropped rather than exporting a prefix that might cut through a sensitive pattern. Explicit core
`.input()` and `.output()` calls remain direct because the caller has already chosen to capture
that value.

## Asynchronous observation and context lifecycle

Asynchronous wrappers use raw OpenTelemetry spans and never retain a thread-affine `Scope` for the
operation's lifetime. The complete invocation context is restored only around same-thread method,
subscription, signal, scheduled-task, or callback boundaries, and each short-lived scope closes on
the thread that opened it.

Spring AI streams and Reactor-returning `@ObserveGeneration` methods create state per subscription.
A terminal-bound scheduler lease covers tasks scheduled while that subscription context is active.
The final wrapper also restores raw Reactive Streams signals, request, and cancel.
`ReactorContextPropagation.wrap(rawPublisher)` lets a provider adapter move the boundary next to a
raw source when its own upstream operators also need the context.

LangChain4j callbacks and model listeners restore the invocation context automatically. Provider
adapters that own task submission can use `LangChain4jStreamingContext.wrap(...)`,
`taskWrapping(...)`, or a fixed per-request `Snapshot`. These adapters do not claim to reach an
opaque provider-owned executor, and they retain no global request registry.

`CompletionStage` observations attach a terminal side effect and return the original stage.
Reactor observations create one span per subscription and end it on completion, error, or
cancellation. Atomic terminal guards prevent concurrent signals from ending a span twice.

The `0.2.x` compatibility claim is JVM-only. Reflective compatibility paths do not imply Spring
AOT or GraalVM native-image support.

## Standalone transport safety

Standalone mode accepts only absolute HTTP(S) hosts without user-info, query, or fragment
components. HTTPS is required by default because OTLP authentication uses a Basic
`Authorization` header. Plaintext HTTP requires an explicit development-only option and is limited
to `localhost` or a literal loopback address.

Contract tests cover the final OTLP path, authentication and ingestion headers, serialized spans,
and redirect refusal.
