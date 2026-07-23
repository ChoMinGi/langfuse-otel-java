# Roadmap

This file tracks release-level work. Completed implementation details live in
[CHANGELOG.md](CHANGELOG.md), and the publication procedure lives in
[RELEASING.md](RELEASING.md).

## 0.2.0 — Spring Boot 3 production preview

The code and build now cover the release scope:

- explicit application-owned or standalone OpenTelemetry, with safe transport and privacy defaults
- isolated Spring AI/Reactor and LangChain4j streaming lifecycles
- type-preserving model instrumentation and completion-aware `@ObserveGeneration`
- local status plus optional Actuator health and metrics
- reproducible artifacts and the release gates documented in [RELEASING.md](RELEASING.md)

Two checks must run against the exact release commit:

- [ ] Run the Java and framework compatibility matrix.
- [ ] Complete Central validation and manual publication, verify public dependency resolution, then
  publish the generated GitHub draft.

## 0.3.0 — Spring Boot 4 adapter line

- Keep the current Maven coordinates and move the starter to Spring Boot 4 and Spring AI 2.
- Keep one framework generation per starter release; the core module remains framework-neutral.
- Prefer Spring AI observation hooks and LangChain4j listener/context hooks where they provide equivalent coverage.

## Before 1.0 RC

- Validate trace hierarchy and attributes through the real Langfuse API.
- Run outage, queue-saturation, shutdown, performance, and soak tests, including leak checks.
- Add Spring AOT/GraalVM runtime hints and a native-image matrix before claiming native support.
- Freeze the supported Java and framework matrix.
- Complete a threat model and external security review.
