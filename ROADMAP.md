# Roadmap

This file tracks release-level work. Completed implementation details live in
[CHANGELOG.md](CHANGELOG.md), and the publication procedure lives in
[RELEASING.md](RELEASING.md).

## 0.2.0 — Published Spring Boot 3 production preview

The code and build now cover the release scope:

- explicit application-owned or standalone OpenTelemetry, with safe transport and privacy defaults
- per-subscription Spring AI/Reactor and LangChain4j lifecycles with terminal-race guards
- type-preserving model instrumentation and completion-aware `@ObserveGeneration`
- local status plus optional Actuator health and metrics
- reproducible artifacts and the release gates documented in [RELEASING.md](RELEASING.md)

Published to Maven Central and GitHub on 2026-08-03. The `0.2.x` line remains a production preview.

## 0.2.1 — Stabilization

Published to Maven Central and GitHub on 2026-09-21 UTC.

- Fix implementation-method annotation resolution through JDK proxies and preserve annotation precedence.
- Surface non-proxyable model methods without changing application behavior.
- Ship the owned-pipeline sampling fix already merged after 0.2.0.
- Patch Netty and Tomcat on the Boot 3 line and restore a passing vulnerability gate.
- Validate compatibility against 0.2.0, the Java/framework matrix, independent consumers, and Docker read-back.

See [STABILIZATION-0.2.1.md](STABILIZATION-0.2.1.md) for acceptance criteria and validation evidence.
Exact-release-commit canary, Central validation/publication, and public artifact resolution remain
release gates in [RELEASING.md](RELEASING.md).

## 0.2.2 — Explicit observation core

- Implemented: Scope-independent observation lifetime, frozen snapshots and capture policy integration.
- Preserved current automatic instrumentation and its framework CI.
- Verified the public consumer and stable Langfuse 4.41.0 read-back.
- Completed owned-pipeline fault/queue/retention checks, a short concurrency matrix, and a representative
  ten-minute warmup plus two-hour run. Broader support changes and default transitions remain separate decisions.

Phase-0 evidence: [CORE-PREFLIGHT.md](CORE-PREFLIGHT.md). API contract: [OBSERVATION-API.md](OBSERVATION-API.md).
Implementation and operational evidence: [OBSERVATION-VALIDATION.md](OBSERVATION-VALIDATION.md).
Release publication follows the gated process in [RELEASING.md](RELEASING.md).

## Deferred — Spring Boot 4 adapter line

- Keep the current Maven coordinates and move the starter to Spring Boot 4 and Spring AI 2.
- Keep one framework generation per starter release; the core module remains framework-neutral.
- Prefer Spring AI observation hooks and LangChain4j listener/context hooks where they provide equivalent coverage.

## Before 1.0 RC

- Extend/repeat outage, queue-saturation, shutdown, performance and soak checks across the supported
  deployment/framework matrix; the current owned-core baseline is one configuration.
- Add Spring AOT/GraalVM runtime hints and a native-image matrix before claiming native support.
- Freeze the supported Java and framework matrix.
- Complete a threat model and external security review.
