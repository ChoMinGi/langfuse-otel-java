# Observation API implementation validation

A/B validation: 2026-09-23 KST; operational validation: 2026-09-24 KST.
Baseline: `33a8926b30d3aac4bb2a42e577146d396a5e69ff` plus
the local A/B implementation. Build version: `0.2.2-SNAPSHOT`; OpenTelemetry: `1.62.0`.
This records pre-release functional and controlled operational validation, not a release
or production performance certification.

## Implemented scope

- Add `LangfuseObservation`, `ObservationType` and `LangfuseOtel.observation(...)`.
- Separate span lifetime from Scope lifetime; serialize attribute commits and one terminal selection.
- Install a frozen context carrier before SDK processors run. Reuse the existing resolver and mapping.
- Apply existing content/exception policies through a new fatal-aware internal path. Preserve legacy helper behavior.
- Add standalone consumer code/tests, starter integration tests, API/migration documentation, and a CI consumer step.
- Keep modules, dependencies of the production artifacts, starter defaults, export ownership and release version unchanged.

## Completed verification

| Command / environment | Result |
|---|---|
| `./mvnw -B -ntp -Pquality clean install`, Java 21.0.8 | PASS: tests, package manifests, Javadoc, dependency convergence, coverage, 0.2.1 API compatibility, SpotBugs, license checks, SBOM generation |
| Default framework tests in that build | core 155 passed; starter 203 passed + 2 conditional skips; package manifest tests 1 per module passed |
| `./mvnw -B -ntp -pl langfuse-otel-core -am test`, Java 11.0.26 | 155 passed, no skips |
| `./mvnw -B -ntp -f consumer-tests/core-observation-consumer/pom.xml verify`, Java 11.0.26 | 5 passed; public packaged API only |
| `./mvnw -B -ntp -Dspring-ai.version=1.1.8 -Dlangchain4j.version=1.18.0 test`, Java 17.0.14 | core 155 + starter 205 passed, no skips; includes the two LangChain4j 1.18 cancellation cases |
| Existing `consumer-tests/core-prompt-consumer` verify | 1 passed |
| Original phase-0 `ExistingHelpersTest` against the new packaged core | 23 passed; legacy helper characteristics preserved |
| `bash experiments/core-preflight/docker/run.sh`, Java 21 | 2 read-back tests passed: four traces × three observations, both new and raw APIs |

The full quality build's core coverage was 90.27% line / 78.63% branch;
starter coverage was 78.89% line / 64.41% branch. Existing thresholds were not lowered.
SpotBugs reported zero findings for both modules after renaming the private prepared-data class
from `PreparedException` to `ExceptionDetails` (it is data, not a Throwable).

The first custom-SDK failure test used the wrong proxy method name for OTel's `storeInContext`;
correcting the test double exercised the intended mutation path. No production fallback behavior
was weakened to satisfy that test.

## Contracts exercised

- Start leaves the caller's context unchanged. Another thread can end an observation while its creating
  thread retains an open scope. Closing that scope restores the original context.
- Default parent selection occurs at start; explicit parents retain trace relationships and unrelated
  Context keys. New snapshots shadow old carriers without stale metadata from the owned processor.
- Ended parents do not end children. Legacy child wrappers inherit the frozen snapshot, while direct
  legacy setters/raw span writes remain outside snapshot mutation guarantees.
- 150 rounds of simultaneous end/fail/cancel yield one coherent exported terminal state. A custom span
  additionally counts the actual `end()` invocation, including when its end operation throws.
- Blocked body and exception redactors do not block cancellation; late prepared values/events are discarded.
  An external onEnd processor can wait for another thread to call into the same observation without a lock deadlock.
- Metadata-only defaults, independent direction opt-in, redaction failure, surrogate-aware truncation,
  error type-only defaults, safe stack rendering, fatal propagation and nonfatal SDK failure containment.
- Negative/overflowing usage is rejected; zero remains an explicit value, and unknown usage stays absent.
- External close/flush does not shut down the application's provider, and external sampling remains effective.
- Starter-created `LangfuseOtel` uses existing policy properties/redactor beans; manual CHAIN + automatic
  generation yields the intended hierarchy without a second usage total at the root.

## Actual Langfuse 4.41.0 results

Pinned revision and image digests remain those recorded in the [phase-0 results](experiments/core-preflight/evidence/results.json).
The runner checks image labels, uses disposable credentials/volumes, requires the v4 ingestion header,
and removes its temporary environment on exit. No paid model call or hosted project is involved.
The final container inventory confirmed the temporary project was removed and the pre-existing Langfuse stack remained running.

| Path | Capture | Trace ID |
|---|---|---|
| New observation API | enabled | `497fb154659fe87099d21ea2f374151d` |
| New observation API | disabled | `b57f1baa40d693c9186aa3d332791244` |
| Existing helpers | enabled | `bc66776e8af2a6d370242097601f45f4` |
| Existing helpers | disabled | `7968cbb717ca1e445f49b56123c43541` |

Each trace contains CHAIN → GENERATION + TOOL with correct parent IDs, timestamps, user/session,
tags, trace name, environment/release/version, model and input/output/total usage (5/7/12).
Disabled input/output remains null. Common metadata filters return all three rows; a per-observation
metadata filter selects the one tool. Missing usage stays an empty map and first-token latency remains null.

Synthetic read-back rows and machine-readable results are preserved in
[observation-api evidence](experiments/core-preflight/evidence/observation-api/results.json).
The live run preceded the private class naming correction; the public API and recording behavior were identical.

## Phase C operational validation

Checkpoint reviewed: 2026-09-24 KST. All core source hashes still match the recorded A/B verification.

The new [operational harness](experiments/core-operations/README.md)
has five passing fault contracts: blocked queue, delayed receiver, HTTP 503, disconnected response,
and bounded shutdown. Queue saturation produced 4,608 observations, accepted 2,560 without duplicate
IDs, and reported exactly 2,048 drops. Failed exports were counted and fresh exports recovered.
On 2026-09-24 all five fault tests passed again in 27.62 seconds, including the additional check that
the batch worker exits after the blocked shutdown receiver is released. The [fresh results](experiments/core-operations/evidence/2026-09-24-faults/results.json)
record the tested harness source hashes.

The short load matrix completed all 27 combinations at concurrency 1/32/128 with no instrumentation,
metadata-only and body capture against normal/delayed/503 receivers. It measured 237,475 operations
over about 81.46 aggregate measurement seconds. Normal-receiver cases reported no export failures or
queue drops. Sampled ended observations were cleared after idle GC; this is not a general leak proof.

This run is not a sustained performance baseline: each combination used a one-second warmup and a
three-second measurement. Additionally, wall-clock/JUnit duration (~32,943 seconds) differs sharply
from monotonic elapsed time (~295 seconds). Machine suspension or a clock discontinuity is possible,
but the cause was not verified. Do not count the wall-clock gap as a long-running soak. Early thread
growth also reflects the harness's lazily started fixed pool. Preserved results and limitations are in
[initial-run evidence](experiments/core-operations/evidence/initial-run/provenance.json).

The revised harness prestarts its worker pool, rejects clock divergence above two seconds and phase
overruns above five seconds, records GC activity and executed class/JAR hashes, and checks SDK/producer
worker counts after every idle period. The previous discontinuous evidence is rejected by the summary
validator. Its replacement [27-case matrix](experiments/core-operations/evidence/2026-09-24-guarded/matrix-clock-guarded.json)
passed with 243,392 measured operations in about 288 seconds overall and at most 9 ms clock divergence.
This remains a short exploratory matrix, not a sustained baseline for every combination.

The current CI-equivalent operational command passed ten tests (five faults, two async retention,
three clock checks); the load test is explicitly skipped without opt-in.
[Results and source hashes](experiments/core-operations/evidence/2026-09-24-guarded/contracts-results.json).
The retention tests confirm that cancellation does not detach an application's pending callback;
completion releases its reference. An abandoned observation is not globally retained or auto-exported.

### Completed sustained run

**PASS**, 2026-09-24 03:48:38–06:00:07 UTC (12:48:38–15:00:07 KST).
The run completed 600 seconds of warmup and 24 × 300-second body-capture windows at concurrency 32,
offering 3,000 operations/s with preallocated 1 KiB input and output. Runtime: Java 17.0.14,
macOS 26.1 arm64, 11 available processors, `-Xms128m -Xmx512m`.

Warmup generated 1,799,742 observations. The measured windows generated **21,599,509** over
7,200.20 measured seconds. Total run duration, including warmup/drain/idle GC, was 7,889.19 monotonic
seconds and 7,889.31 wall seconds. Maximum observed clock divergence was **1,909 ms**, within the
2,000 ms rejection threshold fixed before the run. The final independent summary validation passed;
the threshold was not changed to accept the result.

| Receiver | Measurement time | Observations | Accepted, including recovery/drain | Failed-export spans | Queue drops | Per-window producer p99 |
|---|---:|---:|---:|---:|---:|---:|
| Normal | 60 min | 10,799,633 | 10,799,633 | 0 | 0 | 43.39–49.22 μs |
| HTTP 503 | 30 min | 5,399,981 | 11,776 | 115,200 | 5,273,005 | 42.46–49.47 μs |
| 200 ms response delay | 30 min | 5,399,895 | 4,396,948 | 0 | 1,002,947 | 42.05–45.28 μs |

Every window satisfies `generated = accepted + failed-export spans + queue drops` in this controlled
receiver. Recovery acceptance in the 503 rows is not acceptance while the receiver was returning 503.
Drops under deliberately excessive offered load are expected; there is no durable resend queue.
This accounting does not establish exactly-once delivery for arbitrary network failures.

The first six normal windows provide the requested 30-minute baseline after warmup: **5,399,650**
observations, zero failures/drops, p99 43.55–49.22 μs across windows, and post-GC live heap
11.219–11.232 MiB. Across all 24 windows:

- Post-GC live heap was 11.219–11.512 MiB; after the initial fault/recovery cycle it stayed
  within 11.477–11.512 MiB. No unbounded heap growth was observed at this workload.
- Total live JVM threads were 53 at every idle sample, including exactly 32 producer workers and
  two SDK batch workers. All **768** sampled ended observations were collectible after idle GC.
- Producer allocation averaged about 2,336–2,337 bytes/observation. Exporter/receiver allocation is
  excluded. Measured phases recorded 3,185 GC collections and 4,181 ms of collection time; explicit
  idle collections are outside those phase counters.

The run retained a bounded number of fixture result rows. Weak-reference samples and stable heap/thread
measurements are evidence for this workload, not a universal leak-free guarantee. This was a local
OTLP receiver, not a Langfuse server-capacity or model benchmark. The long run covers one owned-pipeline
configuration; the 27-case matrix remains exploratory, with no 30-minute result for every combination.
No maximum-throughput claim or portable performance regression budget is derived from the offered rate.

Reproducible commands are in the [harness README](experiments/core-operations/README.md).
[Full evidence, accounting and hashes](experiments/core-operations/evidence/2026-09-24-guarded/results.json),
[raw windows](experiments/core-operations/evidence/2026-09-24-guarded/load-soak.json),
and [validated summary](experiments/core-operations/evidence/2026-09-24-guarded/soak-summary.json)
are preserved outside `target/`. No production source change was needed during this operational phase.

## Scope and delivery state

App-root filtering, generalized extended usage/cost handling, provider adapter rewrites and automatic
instrumentation support reduction remain separate work. Existing automatic paths were regression-tested
in A/B; they were not the subject of this core soak. External synchronous processors were not benchmarked.
No universal provider cancellation or leak-free claim is made.

The CI configuration includes the new consumer and short operational contracts with retained evidence.
A separate manual `Core sustained load` workflow runs the long profile with a 180-minute job limit and
validates its JSON. `actionlint` and shell syntax checks passed. The evidence above predates release
preparation: at the time it was captured, there was no commit, PR, hosted CI result or publication.
Release 0.2.2 additionally requires hosted CI, the vulnerability gate, exact-commit Langfuse 4.41.0
read-back, Central validation and public artifact resolution under [RELEASING.md](RELEASING.md).
No community submission is included.
