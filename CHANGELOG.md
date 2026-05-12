# Changelog

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
