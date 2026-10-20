# Observation API

Use `langfuse.observation(name, type)` to trace work that your application starts and finishes.
Available since 0.2.2, it works with synchronous calls and async callbacks. For a complete setup,
start with the [first-trace example](examples/first-trace/).

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

`model`, `prompt`, and `parentContext` belong to your application. `start()` creates an observation
without changing the current context. `makeCurrent()` opens an OTel scope: close it on the same
thread, in reverse nesting order.

In this example, the root scope makes the generation a child of the root. Open a generation scope
as well if work inside the model should create children under it. `close()` ends an observation;
it cannot tell whether the enclosed code failed, so call `fail()` in the catch block.

### Parent and request metadata

An omitted parent comes from `Context.current()` when `start()` runs. Pass `.parent(context)` to
use a captured or remote parent, or `.parent(Context.root())` to start without one.

Metadata comes from the complete `.traceContext(...)` snapshot, then the parent snapshot, then an
empty snapshot with SDK resource defaults. Fields are not merged with worker-thread legacy values.
Set `traceName` on the snapshot if descendants should share one workflow name.

The snapshot is frozen before SDK processors run. Later legacy trace setters cannot change it.
Legacy child wrappers inherit the snapshot, but their fluent setters still write directly to their
own spans. If third-party spans also need these fields, install a processor in your application's
SDK when building it; the library cannot add one to an SDK that already exists.

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
It returns the original provider stage. To create children in a callback, open a scope inside that
callback or pass `observation.context()` as the parent. Propagation into provider-owned executors
must be handled at the provider or application boundary.

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

The first end, failure, or cancellation to commit wins. If `fail()` is still preparing error details,
another thread can end or cancel the observation first. Later writes and terminal calls are ignored,
including argument validation on an ended observation.

Attribute writes and terminal selection use a short lock. Redactors run outside it, and their
results are discarded if the observation ended in the meantime. `span.end()` also runs outside the
lock, so synchronous SDK processors do not hold it. Losing terminal calls need not wait for a
processor to finish.

Ordinary SDK/redactor recording failures do not change the application result. VirtualMachineError,
ThreadDeath and LinkageError raised by instrumentation propagate; `fail()` attempts termination if
exception preparation raises one. Scope attachment/restoration follows OTel context storage semantics.
Explicit invalid arguments on an open observation raise the documented validation exception.
Raw span access through `Span.fromContext(observation.context())` and other SDK components can bypass
these guarantees; they remain application responsibilities.

Each observation ends its span once. Delivery still depends on your SDK and exporter; this does
not guarantee exactly-once delivery over the network. A synchronous processor in an external SDK
can delay the winning end call.

## Capture, SDK limits and migration

Configure `ContentCapturePolicy` and `ExceptionCapturePolicy` on `LangfuseOtel`, or use the
[starter properties and redactor beans](docs/spring-boot.md#input-output-and-exceptions).
Observation input/output calls apply these policies. Legacy `LangfuseTrace`, `LangfuseSpan`, and
`LangfuseGeneration` input/output calls record content directly. A failed redactor drops the value
without logging the original. The fatal-error behavior described above applies to the observation
API; legacy helpers keep their existing behavior.

Token counts use scalar attributes. For extended usage, cost, and prompt fields, use the legacy
helpers or raw OTel attributes. SDK attribute count/value limits still apply; they can drop fields
or truncate structured JSON written through those paths. Check your SDK limits and exported data.

Use a manual CHAIN/AGENT root with automatic model generations for different hierarchy levels.
For the **same** model call, choose manual generation or automatic generation. The core does not
deduplicate arbitrary manual spans, because that could remove nested calls or retries.
For manual instrumentation only, set `langfuse.enabled=false` and provide
your own `LangfuseOtel` bean (including any policies and external SDK). This disables the starter's
auto-configuration; merely supplying a core bean does not disable model instrumentation.

## Prompt versioning with the legacy API

Legacy `trace()`/span/generation handles own a scope. Close them on the thread that created them,
in reverse nesting order; do not pass them to async callbacks. See the
[synchronous lifecycle](DESIGN.md#synchronous-span-lifecycle).

The legacy generation helper can fetch and link prompts through
[langfuse-java](https://github.com/langfuse/langfuse-java). Add the optional client dependency:

```xml
<dependency>
    <groupId>com.langfuse</groupId>
    <artifactId>langfuse-java</artifactId>
    <version>0.2.0</version>
</dependency>
```

Create the client with the same Langfuse credentials and host:

```java
LangfuseClient langfuseClient = LangfuseClient.builder()
        .credentials("pk-lf-...", "sk-lf-...")
        .url("https://cloud.langfuse.com")
        .build();

trace.generation("llm", gen -> {
    String compiled = gen.prompt(langfuseClient, "my-prompt")
            .variable("domain", "HR")
            .variable("question", "What is MBO?")
            .compile();
    // promptName & promptVersion auto-linked to the span
    gen.output(callLLM(compiled));
});
```

This example uses the legacy generation helper. Its `output(...)` records content directly;
the observation capture policy does not apply to that call. See the
[prompt consumer](consumer-tests/core-prompt-consumer) for a complete dependency setup.

## Verification scope

CI checks the core on Java 11/17/21, the independent consumer, starter integration, and Langfuse
4.41.0 read-back. Tests cover parent context, concurrent termination, redaction, capture defaults,
usage validation, and SDK ownership.

[OBSERVATION-VALIDATION.md](OBSERVATION-VALIDATION.md) records the contract, fault, and load results.
The two-hour load run covers one owned-pipeline configuration; it is not a performance guarantee
for other applications or external SDK processors.
