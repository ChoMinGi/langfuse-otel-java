# 0.2.3 release audit

Release commit: `d51cf7dea80303dba6af60849179a8bd642490c6`.
GitHub-verified signed tag: `v0.2.3`, tag object `fe631a78bf9b497ed26538080474b4e332e3b562`.
OpenTelemetry: `1.66.0`. Java 11 core and Spring Boot 3 / Spring AI 1 API compatibility are retained.

## Release gates

| Gate | Evidence |
|---|---|
| Release PR CI | [39092172465](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/39092172465) |
| Exact main commit CI | [39092416792](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/39092416792) |
| Signed-tag gates and Central validation | [39092668590](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/39092668590) |
| Hosted sustained-load profile | [39092423633](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/39092423633) |

The PR, main and signed-tag gates passed, including Java 11/17/21, supported Spring AI/LangChain4j
combinations, package/Javadoc/coverage/API compatibility, independent consumers, operational
contracts, static analysis, licenses and the vulnerability gate. The optional shared-project export
smoke was skipped; the independent pinned Langfuse 4.41.0 Docker read-back gate passed on main
and the signed tag.

## Dependency security scope

The main and tag release SBOM scans each contained 83 components, zero HIGH/CRITICAL findings
and one MEDIUM finding (`CVE-2026-49844`, Log4j API 2.24.3). Jackson BOM 2.21.8 is selected in the
library and independent examples/consumers. Optional MVC/WebFlux server starters were replaced by
the Spring Web, Servlet and Reactor APIs required by the starter filters. WebFlux remains test-only
for compatibility verification. The vulnerability gate was not relaxed and no suppressions were added.

This result covers the published library dependency graph, including optional dependencies and
excluding test dependencies. It does not certify the Boot 3 web examples or a downstream application's
web stack: Spring MVC/WebFlux 6.2.19 remains affected there. See [SECURITY.md](SECURITY.md) for
applicability, the Jackson BOM override and the Spring 7.0.9 OSS / 6.2.20 enterprise fix paths.

## Exact-commit Langfuse read-back

Four synthetic traces each contained CHAIN root, GENERATION and TOOL observations. Capture-on/off,
hierarchy, metadata search, common fields, model usage and root I/O were checked. Every observation
carried the full release commit as its version and `0.2.3` as its release. Main CI trace IDs:

- Explicit API, capture on: `d784ea7e7dc1f7d93f701104fcfb41ec`
- Explicit API, capture off: `a6474497b517c713ed2dfb89bd3fbfdc`
- Raw helpers, capture on: `a42639bab498b76cbed226445ee5c8d1`
- Raw helpers, capture off: `970db38d2f6733b4cd3869bd498dd2b4`

## Release artifact load validation

Hosted Linux amd64, Java `17.0.20.1+1`, four available processors, 128–512 MiB JVM heap,
32 producer threads, two owned SDK batch workers, fixed 1 KiB input/output and 3,000 offered ops/s.
Ten-minute warmup followed by `7200.0301264360005` measured seconds across 24 windows. The full run took
`7888.375857966` monotonic seconds; maximum wall/monotonic divergence was 1 ms.

| Receiver | Observations | Failed export spans | Queue drops | Window p99 recording latency |
|---|---:|---:|---:|---:|
| NORMAL, 60 minutes | 10,800,000 | 0 | 0 | 14.703–23.327 µs |
| 503, 30 minutes | 5,400,000 | 116,224 | 5,273,248 | 14.367–22.719 µs |
| SLOW 200 ms, 30 minutes | 5,400,000 | 0 | 874,432 | 15.439–21.743 µs |

All 21,600,000 observations were accounted for: 15,336,096 accepted including recovery/drain,
116,224 failed exports and 6,147,680 bounded-queue drops. Drops under injected faults/overload are
expected; this does not provide durable or exactly-once delivery. Post-GC live heap ranged
11.33–11.60 MiB, idle JVM thread count stayed at 53, and all 768 sampled ended observations were
collectible. Percentiles cover producer recording calls, excluding pacing, flush and networking.
This is one controlled receiver configuration, not a maximum-throughput, Langfuse capacity or
universal leak-free guarantee.

[Raw measurements](experiments/core-operations/evidence/2026-10-18-release/load-soak.json) and
[validated summary](experiments/core-operations/evidence/2026-10-18-release/load-soak-summary.json)
are preserved in the repository.

## Maven Central verification

Deployment: `c0912cfa-b47c-4c74-a317-52ad201fee12`.
The signed parent POM, both module artifacts/sources/Javadocs and signed CycloneDX SBOM were
uploaded and validated once. Automatic publication was disabled. The existing candidate was then
published by [39100161174](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/39100161174),
which observed `PUBLISHED` on 2026-10-18 at 05:27:49 UTC.

All ten public files and detached signatures were downloaded from Maven Central. Checksums,
POM identities, archive contents and signatures were verified; the signing fingerprint matched
`4772295BF86379CE1D06367065829ECFDDE6A022`. Public SBOM components matched the tag quality scan,
and an independent public-SBOM scan also found zero HIGH/CRITICAL and one MEDIUM issue.

A new temporary Maven cache, restricted to the public Central mirror, was used without installing
the local reactor. The published artifacts passed Spring AI (2 tests), LangChain4j (2), optional prompt
client (1), and explicit observation (5) consumers. Repository-origin records and JAR hashes confirm
that the tested library artifacts came from public Central.

The published core JAR SHA-256 exactly matches the JAR used in the two-hour run:

```text
b65f4af17b09c3d3f7814dbe6f25e420adc9702f3d7c5f8b7ea8aa14c7ff16ee
```

See the [GitHub release](https://github.com/ChoMinGi/langfuse-otel-java/releases/tag/v0.2.3),
[core artifact](https://central.sonatype.com/artifact/io.github.chomingi/langfuse-otel-core/0.2.3)
and [starter artifact](https://central.sonatype.com/artifact/io.github.chomingi/langfuse-otel-spring-boot-starter/0.2.3).
The release includes a validation evidence archive and SHA-256 checksum.
