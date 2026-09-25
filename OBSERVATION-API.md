# Explicit observations (0.2.2)

Version 0.2.2 provides `LangfuseObservation` and `ObservationType` through
`LangfuseOtel.observation(name, type)`. They are not part of the published `0.2.1` artifact.
The existing two Maven modules, fluent wrappers and automatic starter instrumentation remain supported.

## Execution and context

```java
var request = LangfuseTraceContext.builder()
        .userId("analytics-user")
        .sessionId("analytics-session")
        .traceName("answer-question")
        .metadata("workflow", "support")
        .build();

try (var root = langfuse.observation("answer-question", ObservationType.CHAIN)
        .parent(parentContext).traceContext(request).start();
     var scope = root.makeCurrent()) {
    try (var generation = langfuse.observation("chat", ObservationType.GENERATION).start()) {
        try {
            generation.model("application-model").input(prompt);
            var response = model.call(prompt);
            generation.output(response);
            // Record provider-reported counts only, when available:
            // generation.usage(inputTokens, outputTokens);
        } catch (RuntimeException | Error failure) {
            generation.fail(failure);
            root.fail(failure);
            throw failure;
        }
    }
}
```

`model`, `prompt`, and `parentContext` belong to the application. `start()` never changes the
current thread's context. `makeCurrent()` returns an ordinary OTel Scope; close it on the same
thread in reverse nesting order. Opening only the root scope makes the generation a child of
the root; open a generation scope too if the provider or subsequent work should create children
under that generation. Closing an observation only ends its span, and does not infer failure.

Builders resolve an omitted parent from `Context.current()` at **start time**, not builder creation.
An explicit `Context.root()` starts without an upstream parent. Remote-parent trace relationships
are preserved. Metadata selection is whole explicit snapshot, then parent snapshot, then empty
snapshot with existing SDK resource defaults. Worker legacy ThreadLocal values are not merged.
Set `traceName` on the snapshot to copy one workflow name to all descendants.

Each observation installs a frozen carrier before SDK span processors run. Old trace setters
cannot subsequently change this snapshot. Ordinary legacy children inherit it, but legacy fluent
setters still write their own spans directly; unrestricted setter mixing is outside the contract.
An application-owned SDK needs an application-installed processor if it also wants metadata on
third-party spans. The library does not reconfigure an existing SDK.

## Asynchronous work

Create the observation before submission, attach a completion callback, and keep scopes short:

```java
var observation = langfuse.observation("chat", ObservationType.GENERATION)
        .parent(parentContext).start();
try {
    var stage = model.callAsync(prompt);
    stage.whenComplete((response, failure) -> {
        if (failure != null) {
            // Classify provider-specific wrapped cancellation first when applicable.
            observation.fail(failure);
        } else {
            try { observation.output(response); }
            finally { observation.end(); }
        }
    });
    return stage;
} catch (RuntimeException | Error failure) {
    observation.fail(failure);
    throw failure;
}
```

The [compiled consumer example](consumer-tests/core-observation-consumer/src/main/java/com/example/ObservationExample.java)
also demonstrates a short submission scope and unwrapping standard completion/cancellation exceptions.
It returns the original provider stage. A callback creating children must open and close its own
scope, or supply `observation.context()` as the explicit parent. Provider-owned executors require
the provider/application's own propagation. No task, retry or timeout engine is introduced here.

`cancel()` ends only the observation, with status message `cancelled` and without setting OTel ERROR.
It does not cancel the request or its future. An incomplete future may keep its callback and
observation alive. The application must arrange completion or cancellation; there is no automatic
timeout or registry cleanup. Internal provider retries remain one logical call unless the provider
exposes individual attempts. No first-token timestamp is inferred.

## Recording and termination

| Operation | Contract |
|---|---|
| `input(String)`, `output(String)` | Capture policy applies; default disabled. Null is ignored. |
| `metadata(key,value)` | Explicit observation metadata; not automatically redacted. Nonblank key and nonnull value required. |
| `model(name)` | Nonblank model name using Langfuse/GenAI mappings. |
| `usage(input,output)` | Nonnegative long counts, including zero. Sum must fit in long. Omit the call for unknown usage. |
| `end()`, `close()` | Commit normal termination once. `close()` never closes scopes. |
| `fail(Throwable)` | Prepare policy-filtered error details, commit ERROR and end once. Does not rethrow the supplied argument. |
| `cancel()` | Commit cancellation once; actual request cancellation is separate. |
| `context()`, `makeCurrent()` | Remain usable after termination; a later child does not extend its ended parent's duration. |

The first terminal operation to **commit** wins. Calling `fail()` first does not reserve the winner
while its redactor is still working. Attribute commits and terminal selection share a short lock.
Redactors run outside it, followed by an OPEN check; a concurrently ended observation discards the
prepared value. SDK `span.end()` also runs outside it so external synchronous processors can run
without holding the observation lock. Losing terminal calls need not wait for that processor.
Ended observations ignore later mutation calls, including late callbacks and validation.

Ordinary SDK/redactor recording failures do not change the application result. VirtualMachineError,
ThreadDeath and LinkageError raised by instrumentation propagate; `fail()` attempts termination if
exception preparation raises one. Scope attachment/restoration follows OTel context storage semantics.
Explicit invalid arguments on an open observation raise the documented validation exception.
Raw span access through `Span.fromContext(observation.context())` and other SDK components can bypass
these guarantees; they remain application responsibilities.

This API writes one completed span. It adds no re-export/update mechanism or network exactly-once
guarantee. Owned SDK batching/queue/flush/shutdown and external SDK ownership are unchanged. An external
synchronous processor can still delay the winning end call; core does not control its cost.

## Capture, SDK limits and migration

Configure `ContentCapturePolicy` and `ExceptionCapturePolicy` on `LangfuseOtel`, or the existing
starter properties/beans. New input/output calls apply these policies, unlike the old fluent
`LangfuseTrace`/`LangfuseSpan`/`LangfuseGeneration` input/output calls. Redactor failure drops the value;
no original value is logged. Fatal propagation is specific to the new path, preserving old helper behavior.

Token counts use scalar attributes. Existing extended usage, cost and prompt representations remain
available through the legacy/raw OTel paths; this initial API adds no generalized JSON serializer.
External SDK attribute count/value limits still apply. They can drop required fields or truncate
structured JSON written through other paths. Configure adequate limits and verify actual exported data.

Use a manual CHAIN/AGENT root with automatic model generations for different hierarchy levels.
For the **same** model call, choose manual generation or automatic generation. The core does not
deduplicate arbitrary manual spans, because that could remove nested calls or retries.
For a fully explicit path with the starter on the classpath, set `langfuse.enabled=false` and provide
your own `LangfuseOtel` bean (including any policies and external SDK). This disables the starter's
auto-configuration; merely supplying a core bean does not disable model instrumentation.

## Verification scope

Tests cover cross-thread termination, snapshot/legacy boundaries, terminal races, redaction races,
fatal and nonfatal SDK failures, default privacy, usage validation and external SDK ownership.
The independent Java 11 consumer is included in the existing CI consumer job. Core contract tests
run in the existing Java 11/17/21 matrix; starter tests remain in its framework matrix.

The opt-in [4.41.0 Docker read-back](experiments/core-preflight/README.md) additionally exercises the
new API alongside the original helper path. Detailed implementation results are recorded in
[OBSERVATION-VALIDATION.md](OBSERVATION-VALIDATION.md).
The [operational harness](experiments/core-operations/README.md) also passed fault/queue/retention
contracts, a short 27-case matrix, and a ten-minute warmup plus two-hour run for the owned pipeline
at concurrency 32. Its conditions, clock validation and limits are recorded in the validation document.
These results do not establish universal performance or absence of leaks. Existing defaults and
legacy API support remain unchanged.
