# 0.2.2 release audit

Release commit: `bef979f3f0238daf500da6998a07a1bd3f3b08c8`.
GitHub-verified signed tag: `v0.2.2`, tag object `ce5b47912215fe94cb5e5743590df23ee512d410`.
OpenTelemetry: `1.66.0`. Existing fluent APIs and starter defaults remain supported.

## Release gates

| Gate | Evidence |
|---|---|
| PR CI | [36120540570](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/36120540570) |
| Exact main commit CI | [36120934747](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/36120934747) |
| Signed-tag release gates and Central validation | [36125603878](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/36125603878) |
| Hosted sustained-load profile | [36120945616](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/36120945616) |

All four runs passed. Checks include Java 11/17/21, supported Spring AI/LangChain4j combinations,
package/Javadoc/coverage/API compatibility, consumers, operational contracts, static analysis,
licenses and the vulnerability gate (zero High/Critical findings at release scan time).
The independent Langfuse 4.41.0 Docker read-back gate passed on both main and the signed tag;
the optional shared-project export smoke was skipped and is not counted as read-back evidence.

## Exact-commit Langfuse read-back

Four synthetic traces each contained CHAIN root, GENERATION and TOOL observations. Capture-on/off,
hierarchy, metadata search, shared fields and usage were checked. Every observation carried the
full release commit as its version and `0.2.2` as its release. Main CI trace IDs:

- Explicit API, capture on: `ff45f65b2f84285c301971c154f7868b`
- Explicit API, capture off: `922cbc6a1a2e9d95cfc1fe7037b55a37`
- Raw helpers, capture on: `512f4091223f940b17ccf882259d95b0`
- Raw helpers, capture off: `5322055fe5fec1ca73bf3f14959a80b8`

## Release artifact load validation

Hosted Ubuntu/Linux amd64, Java `17.0.20.1+1`, four available processors, 128–512 MiB JVM heap,
32 producer threads, two owned SDK batch workers, fixed 1 KiB input/output and 3,000 offered ops/s.
Ten-minute warmup followed by `7,200.03646041` measured seconds across 24 windows.
The full run took `7,889.848130994` monotonic seconds; maximum wall/monotonic divergence was 1 ms.

| Receiver | Observations | Failed export spans | Queue drops | Window p99 recording latency |
|---|---:|---:|---:|---:|
| NORMAL, 60 minutes | 10,800,000 | 0 | 0 | 15.791–17.663 µs |
| 503, 30 minutes | 5,400,000 | 115,200 | 5,273,536 | 15.247–16.319 µs |
| SLOW 200 ms, 30 minutes | 5,400,000 | 0 | 876,064 | 13.095–17.647 µs |

All 21,600,000 observations were accounted for: 15,335,200 accepted including recovery/drain,
115,200 failed exports and 6,149,600 bounded-queue drops. Queue loss is intentional under the
injected faults/overload; this is not durable or exactly-once delivery. Post-GC live heap ranged
11.23–11.51 MiB, idle JVM thread count stayed at 53, and all 768 sampled ended observations were
collectible. Percentiles cover producer recording calls, excluding pacing, flush and networking.
This is one controlled receiver configuration, not a maximum-throughput, Langfuse capacity or
universal leak-free guarantee. It must not be compared directly with the earlier macOS/1.62.0 run.

[Raw measurements](experiments/core-operations/evidence/2026-09-25-release/load-soak.json) and
[validated summary](experiments/core-operations/evidence/2026-09-25-release/load-soak-summary.json)
are preserved in the repository.

## Maven Central verification

Deployment: `dd04b703-820b-4368-8cf9-252f0893506d`.
The upload included the signed parent POM, both module artifacts/sources/Javadocs and signed
CycloneDX SBOM. Public coordinates were resolved on 2026-09-25 UTC into a new temporary Maven
repository without installing the local reactor. The published artifacts passed Spring AI consumer
(2 tests), LangChain4j consumer (2 tests) and explicit observation consumer (5 tests).

The published core JAR SHA-256 exactly matches the JAR used in the hosted two-hour run:

```text
0ecd199be1066fbb0f9e80befdeb2ee6d40933e7e997f3a5bbfa253632f96c4a
```

The initial publication status poll exceeded ten minutes. Resuming exposed an overly strict
assumption that validation-time PURLs would be unchanged after publication. The publication workflow
now retains the prepublication PURL check, verifies already-published coordinates through public
POMs, and allows thirty minutes for synchronization. No artifact was rebuilt or re-uploaded.

See the [GitHub release](https://github.com/ChoMinGi/langfuse-otel-java/releases/tag/v0.2.2),
[core artifact](https://central.sonatype.com/artifact/io.github.chomingi/langfuse-otel-core/0.2.2)
and [starter artifact](https://central.sonatype.com/artifact/io.github.chomingi/langfuse-otel-spring-boot-starter/0.2.2).
