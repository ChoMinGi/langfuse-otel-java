# Changelog

## [Unreleased]

## [0.2.0] - 2026-07-24

### Added

- Explicit `auto`, `external`, and `standalone` OpenTelemetry ownership modes
- Safe-by-default content and exception capture policies with independent redactors and limits
- Immutable request metadata propagation through OpenTelemetry and Reactor contexts
- Completion-aware `@ObserveGeneration` support for `CompletionStage`, `Mono`, and `Flux`
- Provider-facing Reactor and LangChain4j context adapters for owned scheduling boundaries
- Type-preserving automatic model proxies with provider extension-method delegation
- Immutable runtime status plus optional Actuator health and Micrometer signals
- Reproducible artifacts, a checksum-pinned Maven Wrapper, and release gates for coverage,
  dependency convergence, static analysis, licenses, SBOM vulnerabilities, 0.1.1 API
  compatibility, warning-free Javadocs, framework matrices, and consumer builds
- Standalone OTLP transport contract coverage for payloads, hierarchy, headers, endpoint safety,
  and redirect credential handling

### Changed

- Automatic instrumentation no longer captures model input or output by default
- Exception message and stack capture, HTTP Principal export, and session ID export are opt-in
- Spring AI streaming state and bounded output buffers are created per subscription
- Non-proxyable model beans remain unchanged and explicit model-method annotations take precedence
  over automatic instrumentation
- The Boot 3 line now uses Spring Boot 3.5.16, Spring AI 1.0.9, OpenTelemetry 1.62.0, and Netty
  4.1.136.Final; CI also covers Spring AI 1.1.8 and LangChain4j 1.18.0
- `0.2.x` remains the Boot 3/Spring AI 1 line; Boot 4/Spring AI 2 moves to `0.3.x`
- The standalone exporter now uses the JDK HTTP sender, avoiding OkHttp and Kotlin classpath
  conflicts with the optional `langfuse-java` prompt client
- Maven Central publication stops at `VALIDATED` for manual approval before the GitHub release is
  published

### Fixed

- Streaming completion, error, cancellation, and re-subscription no longer leak or reuse spans
- Oversized raw streaming output and stack-only exception capture no longer expose unsafe prefixes
  or throwable messages
- Spring AI scheduler/source boundaries and LangChain4j callbacks/listeners restore the correct
  observation and request metadata without retaining a cross-thread scope
- Provider spans inherit the wrapper observation, and scope restoration remains correct when a raw
  span ends first
- Application-owned OpenTelemetry SDKs are no longer duplicated, flushed, or shut down by the
  library
- Provider embedding/image methods and proxyable concrete or extension types retain their behavior;
  streaming-only LangChain4j beans are not exposed as synchronous models
- Annotated future and Reactor methods end at the real terminal event without duplicate model spans
- Spring AI document and bulk embedding adapters reject invalid `null` delegate results and record
  the tracing error
- Consumer artifact installation no longer runs a zero-test JaCoCo coverage check

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
