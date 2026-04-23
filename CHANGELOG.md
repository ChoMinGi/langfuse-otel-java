# Changelog

## [Unreleased]

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
- `langfuse-otel-spring-boot-starter`: LangChain4j `ChatModel` auto-instrumentation (zero code)
- `langfuse-otel-spring-boot-starter`: `@ObserveGeneration` annotation
- `langfuse-otel-spring-boot-starter`: `LangfuseContextFilter` for HTTP request context propagation
- Examples for Spring AI and LangChain4j
