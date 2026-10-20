# Roadmap

The current release is **0.2.3**. Development is on `0.2.4-SNAPSHOT`.
The 0.2.x line remains production preview for Spring Boot 3 and Spring AI 1.

## Released

| Version | Main changes | Details |
| --- | --- | --- |
| 0.2.0 | SDK ownership, capture policies, async adapters, and health reporting | [Changelog](CHANGELOG.md) |
| 0.2.1 | JDK proxy annotations, sampling fixes, and dependency updates | [Stabilization record](STABILIZATION-0.2.1.md) |
| 0.2.2 | Explicit observations with independent span/scope lifetimes | [API guide](OBSERVATION-API.md), [release record](RELEASE-0.2.2.md) |
| 0.2.3 | Jackson update and narrower optional web dependencies | [Release record](RELEASE-0.2.3.md) |

All four releases are on Maven Central. Current API compatibility checks use 0.2.3 as the baseline.
The remaining Boot 3 web-framework limitations are documented in
[SECURITY.md](SECURITY.md#spring-62-web-framework-limitations).

## Spring Boot 4 adapter line — deferred

- Keep the current Maven coordinates and move the starter to Spring Boot 4 and Spring AI 2.
- Keep one framework generation per starter release; the core module remains framework-neutral.
- Prefer Spring AI observation hooks and LangChain4j listener/context hooks where they provide equivalent coverage.

This work has no release date yet. Existing automatic instrumentation and its framework CI remain
supported in 0.2.x.

## Before 1.0 RC

- Test outages, queue saturation, shutdown, and sustained load across more deployment/framework
  combinations. The [current load results](OBSERVATION-VALIDATION.md) cover one owned-core configuration.
- Add Spring AOT/GraalVM runtime hints and native-image tests before supporting native builds.
- Freeze the supported Java and framework matrix.
- Complete a threat model and external security review.
