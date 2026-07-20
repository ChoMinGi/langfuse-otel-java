# Changelog

## [Unreleased] - 0.2.0 Production Preview

### Added
- Application-owned OpenTelemetry mode via `LangfuseOtel.externalBuilder(OpenTelemetry)`
- Spring auto-configuration reuses a unique application `OpenTelemetry` bean without owning its lifecycle
- `ContentCapturePolicy` with independent input/output opt-in, `ContentRedactor`, and an 8,192-unit post-redaction limit
- `ExceptionCapturePolicy` with type-only defaults and independent redacted message/stack-trace opt-ins
- Explicit Spring `langfuse.otel-mode=auto|external|standalone` ownership selection
- Immutable `LangfuseTraceContext` propagation through OpenTelemetry Context and Reactor Context
- Completion-aware `@ObserveGeneration` support for `CompletionStage`, Reactor `Mono`, and Reactor `Flux`
- Type-preserving automatic model proxies with provider extension method delegation and idempotent advice detection
- Per-subscription, reference-counted Reactor Scheduler propagation leases for Spring AI streams and Reactor-returning `@ObserveGeneration` methods
- `ReactorContextPropagation.wrap(Publisher)` for raw-thread source signals and provider-side Reactive Streams operators
- `LangChain4jStreamingContext` terminal-aware fixed snapshots, task/executor wrappers preserving scheduled capabilities, and listener-attribute accessors for provider integration boundaries
- OpenAI streaming provider contract coverage with a configured JDK HTTP executor at LangChain4j 1.0.0 and 1.18.0
- Immutable operational snapshots through `LangfuseOtel.getStatus()` and optional Actuator/Micrometer signals for ownership, fail-safe fallback, standalone export failures, queue drops, and flush state
- Maven Wrapper with a pinned distribution checksum
- Reproducible archive metadata through a fixed Maven build output timestamp
- Contract tests for SDK ownership, scope restoration, safe content defaults, reactive context isolation, streaming lifecycle, and standalone OTLP transport security

### Changed
- Automatic Spring AI, LangChain4j, and `@ObserveGeneration` content capture is metadata-only by default
- Automatic and direct wrapper exception capture records only the exception type by default
- HTTP Principal and session ID extraction now require independent explicit opt-in
- Spring AI streaming spans and accumulators are created per subscription with `Flux.deferContextual`
- Automatic model BeanPostProcessors preserve proxyable provider concrete types and declared extension interfaces
- Automatic model BeanPostProcessors participate in Spring's early-singleton-reference phase so an explicitly enabled circular dependency and the final singleton share one instrumented proxy
- Final/non-proxyable model beans are kept unchanged and reported as uninstrumented instead of being replaced by an incompatible decorator
- Explicit `@ObserveGeneration` model methods take bean-level precedence over automatic model instrumentation
- WebFlux request metadata no longer relies on a request-lifetime ThreadLocal
- CI runs `./mvnw -B -ntp clean verify`, including packaging, sources, and Javadoc generation
- Release validation now requires tag/POM/docs/examples consistency plus the Java/framework and consumer gates; Central publishing stops for manual approval
- Live Langfuse CI reports an explicit opt-in status and fails on missing credentials once enabled
- Standalone hosts are validated and require HTTPS; the development-only HTTP opt-in is restricted to loopback hosts

### Fixed
- Re-subscribing to a Spring AI stream no longer shares a span or output accumulator
- Unsubscribed streams no longer create orphan spans
- Stream completion, error, and cancellation end each span exactly once
- Enabled streaming output buffers are bounded before terminal redaction/export
- Raw streaming outputs that exceed the pre-redaction bound are dropped instead of exporting a potentially unsafe prefix
- Stack-only exception capture no longer leaks messages from the throwable, causes, or suppressed exceptions
- Legacy context setters and `clear()` override scoped immutable metadata for subsequent synchronous spans
- Embedding dimension and image-edit provider methods are delegated without behavioral regression
- Synchronous provider spans created inside raw wrappers inherit the wrapper observation as parent
- `SpanGuard` restores the previous OTel Scope even if the raw span was ended first
- Cleaner fallback no longer closes a thread-affine Scope from the Cleaner thread
- Explicit parent contexts no longer inherit unrelated current-request metadata
- Existing application-owned OpenTelemetry SDKs are no longer duplicated or shut down by the library
- Automatic BeanPostProcessor instrumentation no longer exposes streaming-only LangChain4j beans as synchronous `ChatModel` candidates
- `CompletionStage` observations retain the original stage identity and end only at success, failure, or cancellation
- Reactor annotation observations create no span before subscription and isolate repeated subscriptions
- Spring AI provider work started while the source subscription is entered inherits the wrapper observation as parent
- Reactor tasks scheduled under an active instrumented subscription inherit its wrapper context; terminal lease closure prevents already-decorated delayed tasks from restoring an ended observation and preserves independently keyed hooks
- Raw Publisher signals, request, and cancel restore the per-subscription context without requiring a Reactor Scheduler, while terminal and concurrent in-flight boundaries close their lease without thread-context leakage
- Spring AI stream assembly and Reactor terminal callbacks retain the full wrapper context until downstream terminal delivery returns
- LangChain4j streaming callbacks and model listeners restore the wrapper observation and immutable request metadata without retaining a cross-thread Scope
- LangChain4j late, queued, and periodic executor work no longer restores a wrapper span after terminal cleanup; reentrant cancellation no longer deadlocks with a provider terminal acknowledgement
- Model methods no longer emit duplicate automatic and `@ObserveGeneration` spans

## [0.1.1] - 2026-05-12

### Added
- `langfuse-otel-core`: `LangfuseContextSpanProcessor` — auto-propagates userId, sessionId, tags, environment to all child spans
- `langfuse-otel-core`: Javadoc for all public API classes
- `langfuse-otel-spring-boot-starter`: `LangfuseReactiveContextFilter` for WebFlux applications
- CI compatibility matrix: Spring AI 1.0.0 / 1.1.6 / 2.0.0-M6, LangChain4j 1.0.0 / 1.14.1

### Fixed
- `completion_start_time` format: epoch millis → ISO 8601 (Langfuse OTLP spec)
- LangChain4j streaming: `StringBuilder` → `StringBuffer` for thread safety
- `LangfuseReactiveContextFilter`: session ID race condition (`subscribe()` → `Mono.zip()`)

### Changed
- Surefire `excludedGroups` extracted to Maven property for CLI override

## [0.1.0] - 2026-05-08

### Added
- `langfuse-otel-core`: Builder pattern OTel SDK wrapper (`LangfuseOtel`)
- `langfuse-otel-core`: `LangfuseTrace`, `LangfuseGeneration`, `LangfuseSpan` with callback, try-with-resources, and manual `end()` APIs
- `langfuse-otel-core`: `LangfuseContext` ThreadLocal propagation for userId, sessionId, tags, environment
- `langfuse-otel-core`: Automatic error capture with OTel status and exception recording
- `langfuse-otel-core`: `SpanGuard` Cleaner-based span leak detection
- `langfuse-otel-core`: Graceful degradation — no-op mode when API keys are absent
- `langfuse-otel-core`: Prompt management integration via `langfuse-java` SDK (optional)
- `langfuse-otel-spring-boot-starter`: Auto-configuration with `application.yml` properties
- `langfuse-otel-spring-boot-starter`: Spring AI `ChatModel` auto-instrumentation (zero code)
- `langfuse-otel-spring-boot-starter`: Spring AI streaming `ChatModel.stream()` auto-instrumentation
- `langfuse-otel-spring-boot-starter`: Spring AI `EmbeddingModel` auto-instrumentation (zero code)
- `langfuse-otel-spring-boot-starter`: Spring AI `ImageModel` auto-instrumentation (zero code)
- `langfuse-otel-spring-boot-starter`: LangChain4j `ChatModel` auto-instrumentation (zero code)
- `langfuse-otel-spring-boot-starter`: LangChain4j `StreamingChatModel` auto-instrumentation
- `langfuse-otel-spring-boot-starter`: LangChain4j `EmbeddingModel` auto-instrumentation (zero code)
- `langfuse-otel-spring-boot-starter`: LangChain4j `ImageModel` auto-instrumentation (zero code)
- `langfuse-otel-spring-boot-starter`: `@ObserveGeneration` annotation
- `langfuse-otel-spring-boot-starter`: `LangfuseContextFilter` for HTTP request context propagation
- `langfuse-otel-spring-boot-starter`: `completion_start_time` attribute for streaming TTFT measurement
- Examples for Spring AI and LangChain4j
