# Core operational validation

This standalone test project exercises the installed core artifact selected by `langfuse-otel.version` against a
synthetic loopback OTLP/HTTP receiver. It does not call a model or a hosted Langfuse project.
Install the reactor artifacts before running it; its dependencies do not change the library POMs.

## Fault contracts

```sh
./mvnw -B -ntp -f experiments/core-operations/pom.xml verify
```

`OwnedPipelineTest` exercises the public owned builder with its existing defaults:
blocked export and queue saturation, delayed response, persistent HTTP 503, disconnected responses,
recovery, and bounded shutdown wait. Payloads are decoded as protobuf. The short no-fault recovery
cases check accepted IDs for duplicates. Load mode retains counters only.
Exporter warnings are expected in fault cases; assertions and the Maven exit status determine success.

`TimingGuardTest` rejects the old discontinuous run's clock values and backward/forward clock gaps.
`AsyncRetentionTest` checks that a pending application callback retains a cancelled observation until
the provider finishes, and that an abandoned observation is not globally registered or automatically exported.

All five tests passed again on 2026-09-24 with the additional assertion waiting for the batch worker
to exit after releasing the blocked receiver (27.62 seconds, Java 17.0.14).
See the [dated results](evidence/2026-09-24-faults/results.json).
The final CI-equivalent verification passed all ten fault/retention/clock tests, with the opt-in load
test skipped: [contract results](evidence/2026-09-24-guarded/contracts-results.json).

## Exploratory load matrix

```sh
./mvnw -B -ntp -f experiments/core-operations/pom.xml test \
  -Dtest=LoadTest -Doperations.load=true \
  -Doperations.warmupSeconds=1 -Doperations.measureSeconds=3
```

Runs concurrency 1/32/128 × no instrumentation/metadata/body × normal/200 ms delayed/503 responses.
Input and output are fixed, preallocated 1 KiB strings. The aggregate offered rate defaults to
3,000 operations/s; late workers do not issue catch-up bursts. Histograms measure the producer's
observation operation, including Scope, attributes and end, excluding pacing and flush.
Allocation uses the producer thread's HotSpot counter; exporter and receiver allocation is excluded.
Percentiles are exploratory observations, not maximum throughput or a performance budget.
The `none` case skips observation calls inside the same fixture process; the two idle/active SDK
clients and receiver still exist. It is a producer-operation control, not a separate application's
whole-process memory baseline. Cases share one JVM and are not randomized. External synchronous SDK
processors and provider/model execution are not benchmarked.

JSON under `target/operations/` contains counts, latency, producer allocation, post-GC live heap,
threads, sampled ended-observation retention, and export/drop accounting. Tests check Scope restoration
and sampled weak-reference clearance. These samples do not prove that all objects are free of leaks.
The revised fixed worker pool is prestarted (128 threads for the matrix, configured concurrency for
soak). Each idle sample checks this exact worker count and two SDK batch workers. GC collections/time
are recorded separately from the explicit idle collections. Executed class and core JAR hashes are
included in the report. Long operations outside the histogram range are rejected instead of clipped.

## Long-run profile

```sh
bash experiments/core-operations/run-soak.sh
```

Defaults request 600 seconds of warmup, followed by 24 × 300-second body-capture cycles at concurrency
32. The first six cycles use a normal receiver; later cycles alternate 503, delay and normal service.
Drain/recovery and idle-GC time are additional to the requested measurement durations.
The runner holds an idle-sleep assertion for the Maven process on macOS. It does not prevent lid-close
or forced suspension. The profile's existence alone is not evidence of two-hour stability; only a
completed, clock-validated result qualifies. Current execution results are recorded in the root
[validation document](../../OBSERVATION-VALIDATION.md).

Use an uninterrupted machine. The revised harness compares wall and monotonic clocks at phase
boundaries and once a second while waiting for workers; divergence above two seconds rejects the run.
A phase overrun above five seconds also rejects the sample. Progress is printed every 30 seconds.
Completed windows are saved incrementally with `complete: false` until the whole run finishes.

```sh
python3 experiments/core-operations/summarize.py \
  experiments/core-operations/target/operations/load-soak.json
```

The summary independently validates clocks, worker counts, sample clearance and span accounting.
It labels a two-hour profile complete only when the run includes at least 600 seconds of warmup and
7,200 requested measurement seconds. Percentile ranges describe separate windows, not a merged p99.

## Preserved evidence and limits

The [clock-validated run on 2026-09-24](evidence/2026-09-24-guarded/results.json) completed the ten-minute
warmup and two-hour profile: 21,599,509 measured observations, zero failures/drops in normal windows,
stable worker counts and all 768 sampled ended observations collectible after idle GC. A separate
corrected 27-case short matrix passed with 243,392 operations and 9 ms maximum clock divergence.
Read the root validation document for exact conditions and limits; this is one owned-pipeline baseline.

The [initial run](evidence/initial-run/provenance.json) preserves five passing fault tests and all
27 passing short load combinations (237,475 measured operations). The matrix used one-second warmups
and three-second measurements, totaling about 81.46 measured seconds.

Wall-clock/JUnit duration was about 32,943 seconds, while the runner's monotonic duration was about
295 seconds. The cause was not independently established. This discontinuity makes the record
unsuitable as a continuous sustained performance baseline or a nine-hour soak result.

The main CI workflow runs the short fault/retention/clock contracts and retains their artifacts.
The separate `Core sustained load` workflow is manual and bounded to 180 minutes; it performs the
full profile and validates the resulting JSON. No hosted CI execution is claimed before these local
changes are submitted and run.
