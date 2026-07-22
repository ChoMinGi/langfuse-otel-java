# Design Decisions

Key architectural and implementation decisions for langfuse-otel-java.

---

## 1. OTel-based, not direct Langfuse API

We wrap OpenTelemetry SDK instead of calling the Langfuse ingestion API directly.

**Why:** Langfuse's maintainer explicitly recommends OTel for Java tracing ([langfuse-java #2](https://github.com/langfuse/langfuse-java/issues/2#issuecomment-2706738123), [#24](https://github.com/langfuse/langfuse-java/issues/24#issuecomment-2698403123)). OTel is vendor-neutral — users can send traces to Langfuse and other backends (Datadog, Jaeger) simultaneously. The Langfuse OTLP endpoint handles `gen_ai.*` semantic conventions natively, so we don't need to reinvent trace data modeling.

**Alternative considered:** Direct API calls (like [yuvenhol/langfuse_java](https://github.com/yuvenhol/langfuse_java)). Rejected because it creates Langfuse lock-in and duplicates what OTel already does well.

---

## 2. Three synchronous API styles (callback, try-with-resources, manual end)

```java
// Callback
langfuse.trace("flow", trace -> { ... });

// Try-with-resources
try (LangfuseTrace trace = langfuse.trace("flow")) { ... }

// Manual
LangfuseTrace trace = langfuse.trace("flow");
trace.end();
```

**Why:** Different Java codebases have different synchronous control-flow styles. Callback is cleanest for new code, try-with-resources is idiomatic Java, and manual `end()` supports explicit lifecycle management when a lexical resource block is inconvenient.

**Contract:** Every wrapper retains an OpenTelemetry `Scope` for its lifetime, so it must be closed on its creating thread and in reverse creation order. Manual `end()` changes syntax, not this thread-affinity rule. Asynchronous instrumentation uses raw spans and short-lived same-thread scopes instead.

**Trade-off:** Three patterns means more API surface to maintain and test. We accept this because callback wraps try-with-resources internally and all three share one lifecycle contract.

---

## 3. failSafe defaults to true (no-op on missing keys)

```java
LangfuseOtel.builder().build()  // no keys → returns no-op, never throws
```

**Why:** An observability library must never crash the host application. If API keys are misconfigured, the app should run normally — just without tracing. This is critical for production safety. Users who want strict validation can use `.failSafe(false)`.

**Precedent:** Honeycomb OTel Java SDK, Datadog Java tracer — both silently degrade when misconfigured.

---

## 4. SpanGuard with java.lang.ref.Cleaner

When a span is not properly closed, `SpanGuard` logs a WARNING and ends the span through a `Cleaner` action.

**Why:** Forgetting to close a span (especially with the manual `end()` pattern) is a common mistake. Ending an abandoned span prevents it from remaining unexported and the warning exposes the lifecycle error during development.

**Limitation:** A Cleaner runs on another thread, so it never owns or closes the originating thread's `Scope`; it cannot repair that thread's context stack. GC-based cleanup may also not run before JVM shutdown. It is a span-ending safety net, not a substitute for try-with-resources or a correctly placed `end()`.

---

## 5. AtomicBoolean for close() guard

```java
private final AtomicBoolean closed = new AtomicBoolean(false);

public void close() {
    if (closed.get()) return;
    verifyCreatingThreadAndLifoOrder();
    if (closed.compareAndSet(false, true)) closeScopeThenClean();
}
```

**Why:** Calling `scope.close()` twice can corrupt the OTel context stack. The guard keeps repeated `close()` and `end()` calls idempotent and tells the Cleaner whether it should report an abandoned span.

**Why not volatile boolean:** `if (!closed) { closed = true; }` is a check-then-act race. Two threads can both see `closed == false` and both enter the block.

---

## 6. close() restores Scope before the Cleaner ends the span

```java
public void close() {
    if (closed.get()) return;
    verifyCreatingThreadAndLifoOrder();
    if (closed.compareAndSet(false, true)) {
        try {
            scope.close();
        } finally {
            cleanable.clean(); // ends the span; never closes Scope
        }
    }
}
```

**Why:** Scope restoration is thread-affine and must happen on the creating thread in LIFO order. Both checks run before the terminal state changes, so an invalid close fails without ending the span and can be retried correctly. The Cleaner owns only span termination; this makes it impossible for the Cleaner thread to close a user thread's Scope. The `finally` still ends the span if Scope restoration itself fails.

---

## 7. AbstractLangfuseSpan base class

`LangfuseTrace`, `LangfuseGeneration`, `LangfuseSpan` all extend `AbstractLangfuseSpan`.

**Why:** `recordException()`, `close()`, `end()`, `getSpan()`, and SpanGuard registration were copy-pasted across all three classes (~40 lines each). A bug fix in one had to be replicated in all three. The base class eliminates this duplication while keeping subclass-specific behavior (e.g., Generation has `model()`, Trace has `userId()`).

---

## 8. gen_ai.operation.name defaults to "chat" but is overridable

```java
new LangfuseGeneration(tracer, "my-gen")  // default: "chat"
gen.operationName("embeddings")            // override
```

**Why:** `gen_ai.operation.name` determines how Langfuse classifies the observation — `"chat"` → GENERATION, `"embeddings"` → EMBEDDING. Most LLM calls are chat completions, so defaulting to `"chat"` is the right 80/20. For embeddings, image generation, etc., users override via `operationName()`.

---

## 9. Tags as OTel array attribute, not comma-separated string

```java
span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), Arrays.asList(tags));
```

**Why:** Langfuse's OTel ingestion endpoint parses array attributes natively. Comma-separated strings are ambiguous if a tag itself contains a comma. OTel's attribute API supports typed arrays — using them is strictly correct.

---

## 10. Decorator wraps all ChatModel overloads via interface delegation

`TracingSpringAiChatModel` implements `ChatModel`, which means `call(String)` and `call(Message...)` (default methods that delegate to `call(Prompt)`) are automatically covered. The tracing wrapper only needs to override `call(Prompt)` and `stream(Prompt)` — the default methods route through them.

**Why:** Earlier AOP-based approaches required explicit pointcut matching for each overload. The Decorator pattern avoids this entirely — implementing the interface guarantees all entry points are covered.

---

## 11. LangfuseGeneration constructor is public

Normally, `LangfuseGeneration` should only be created via `trace.generation("name")`. The constructor is public because tracing wrappers (in the separate `spring-boot-starter` module) need to create instances directly: `new LangfuseGeneration(tracer, name)`.

---

## 12. Object type for langfuseClient

```java
public Builder langfuseClient(Object langfuseClient) { ... }
public LangfusePromptHelper prompt(Object langfuseClient, String promptName) { ... }
```

**Why:** `com.langfuse:langfuse-java` is an optional dependency. Using the concrete type `LangfuseClient` in the public API would cause `NoClassDefFoundError` for users who don't have it on their classpath — even if they never call `prompt()`. Using `Object` defers the class loading to the point of use, where we guard it with `Class.forName()` check.

**Trade-off:** No compile-time type safety. Users can pass any object and get a `ClassCastException`. This is acceptable because the prompt API is an advanced feature, and the error message is clear.

---

## 13. Raw OTel Span for streaming (no lifetime Scope)

Streaming tracing wrappers (`TracingSpringAiChatModel.stream()`, `TracingStreamingLangChain4jChatModel`) use the raw OTel `Span` API instead of `LangfuseGeneration`.

```java
Context parent = Context.current();
Span span = tracer.spanBuilder(name)
    .setParent(parent)
    .setSpanKind(SpanKind.CLIENT)
    .startSpan();
Context invocationContext = parent.with(span);
// makeCurrent() is used only for a same-thread delegate, subscription, or callback boundary.
```

**Why:** `AbstractLangfuseSpan` calls `span.makeCurrent()` in its constructor, pushing the span onto the thread-local OTel context stack. This is correct for synchronous flows where the span opens and closes on the same thread. For streaming, responses arrive on different threads (Reactor schedulers for Spring AI's `Flux`, callback threads for LangChain4j's `StreamingChatResponseHandler`). Calling `makeCurrent()` on the originating thread and `scope.close()` on a callback thread corrupts the context stack.

**How it works:** The full invocation context (wrapper span plus immutable Langfuse metadata) is restored only around a synchronous delegate call, source subscription, Reactor-scheduled task, or LangChain4j callback/listener invocation. Each short-lived Scope is closed on the same thread and boundary; no Scope is retained for the asynchronous lifetime. The span is ended in terminal signals (`doOnComplete`/`doOnError`/`doOnCancel` for Flux, `onCompleteResponse`/`onError`/cancellation for callbacks). An `AtomicBoolean` guard prevents double-end from concurrent terminal signals.

**Trade-off:** Some code duplication — the attribute-setting logic from `LangfuseGeneration` is replicated as helper methods in the streaming wrappers, since `LangfuseGeneration` retains the Scope created by its constructor for its lifetime. This is acceptable because extracting a shared utility would require modifying the core module for a starter-only concern.

---

## 14. Type-preserving BeanPostProcessor proxies for auto-instrumentation

The starter uses a shared `SmartInstantiationAwareBeanPostProcessor` base to install a programmatic Spring AOP proxy around model beans. The proxy routes only framework model methods through the tracing decorators and delegates provider extension methods directly to the original target.

```java
abstract class AbstractModelBeanPostProcessor
        implements SmartInstantiationAwareBeanPostProcessor {
    public Object getEarlyBeanReference(Object bean, String beanName) {
        return instrumentAndRememberEarlyTarget(bean, beanName);
    }

    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return reconcileEarlyReferenceOrInstrument(bean, beanName);
    }
}
```

**Why:** Replacing a provider bean with an unrelated interface decorator removes its concrete type, provider extension interfaces, and any additional model roles. It also made a streaming-only LangChain4j bean appear to implement synchronous `ChatModel`. A class proxy keeps the original assignability and interface set, while a shared advisor marker makes repeated BeanPostProcessor passes idempotent.

**Fail-safe boundary:** A final provider class, final model method, or externally callable final extension method cannot be safely intercepted/delegated with a type-preserving class proxy. In that case the original bean is returned unchanged and a warning identifies the instrumentation gap. Existing JDK proxies are wrapped through their existing interface set. The longer-term adapter direction remains framework-native Spring AI observations and LangChain4j listeners where their versioned APIs provide equivalent coverage.

When singleton circular references are explicitly enabled, the shared post-processor creates the model proxy in `getEarlyBeanReference`, records the ultimate target by bean name, and avoids adding a second model proxy in `postProcessAfterInitialization`. Spring can therefore promote the one early proxy as the final singleton, preserving identity and one advice chain. This does not make a final or otherwise non-proxyable model instrumentable; the existing fail-safe boundary still applies.

**Explicit annotation precedence:** If the ultimate model target, an inherited model implementation, or one of its model interfaces declares `@ObserveGeneration` on a model method, automatic model instrumentation is skipped for the entire bean. This guarantees one observation rather than nested automatic and annotation spans; each model entry point that still needs tracing must be annotated explicitly. Non-model service annotations do not affect model proxying.

---

## 15. Explicit OpenTelemetry ownership

`LangfuseOtel.builder()` is standalone mode and owns the SDK/exporter it creates. `LangfuseOtel.externalBuilder(openTelemetry)` is application-owned mode and creates no SDK or exporter.

**Why:** A process should normally have one OpenTelemetry SDK. Creating another SDK inside an instrumentation library can duplicate telemetry, split traces, compete for resources, and shut down infrastructure owned by the host application.

**Lifecycle contract:** `flush()` and `close()` affect only an internally owned SDK. They are intentional no-ops in external mode. Resource attributes, export routing, batching, and shutdown are then the application's or Collector's responsibility.

---

## 16. Metadata-only automatic content capture

Automatic framework instrumentation does not attach model input or output by default. Users opt in independently for each direction through `ContentCapturePolicy` or Spring properties. Enabled values pass through a user redactor and a finite post-redaction length limit.

**Why:** Prompts, completions, embedding text, and image prompts frequently contain personal data, credentials, or proprietary content. Observability metadata should be useful without silently expanding the application's data boundary.

**Compatibility:** Explicit core fluent calls to `.input()` and `.output()` remain direct because making such a call is itself an intentional capture decision. The policy governs only automatic instrumentation.

---

## 17. Immutable async request context

`LangfuseTraceContext` is immutable and can be stored in OpenTelemetry Context or Reactor Context. The span processor reads the supplied `parentContext` first. Servlet filters use a scoped OTel context; WebFlux filters store request metadata in Reactor Context rather than holding a ThreadLocal open for the request lifetime.

**Why:** ThreadLocal values do not follow scheduler switches and can leak between concurrent reactive requests. An immutable subscription context preserves request isolation and makes the propagation boundary explicit.

---

## 18. Type-only exception capture by default

Automatic instrumentation and direct span wrappers retain `exception.type` while omitting message and stack trace by default. `ExceptionCapturePolicy` enables each detail independently, applies an application redactor, and enforces a finite post-redaction limit.

**Why:** Provider exceptions can include response bodies, prompt fragments, URLs, and credentials. Treating exception details as harmless metadata would bypass the same privacy boundary used for model input and output.

---

## 19. Bounded asynchronous context bridges

Raw-span wrappers retain no lifetime Scope. Spring AI streams and Reactor-returning `@ObserveGeneration` methods acquire a per-subscription scheduler lease. A keyed `Schedulers.onScheduleHook` captures tasks only while the lease-bearing invocation context is current. Each task checks the lease again at execution time; completion, error, or cancellation closes the lease idempotently after signal boundaries already admitted before terminal have returned, and the last lease removes only this library's keyed hook. Thus a task that starts after termination cannot restore an ended observation, already-running work has an explicit in-flight meaning, and unrelated Reactor hooks remain installed.

The final automatic source wrapper also restores raw Reactive Streams signals, `request`, and `cancel`, even when a source uses a plain thread rather than a Reactor Scheduler. That wrapper is downstream of any operators a provider already assembled. `ReactorContextPropagation.wrap(rawPublisher)` therefore exposes a public per-subscription boundary that a provider can place immediately above its raw source when its own upstream `map`/`filter` callbacks must see the observation context. No Reactive Streams adapter can instrument arbitrary provider code that runs before one of those boundary calls.

The LangChain4j streaming wrapper makes the full invocation context current around `doChat` and every user callback. Its listener adapter also carries the captured context in listener attributes. Provider code that owns its scheduling boundary can use submission-time `LangChain4jStreamingContext.wrap(...)`/`taskWrapping(...)`, or retain a fixed `Snapshot` per request when submission happens later from an unscoped control thread. The adapters preserve `ExecutorService` and `ScheduledExecutorService` capabilities. A snapshot atomically admits each wrapper execution against terminal cleanup: work admitted later still runs without the ended span, while already in-flight work finishes under its captured context without delaying span completion. No global request registry is retained. The generic SPI cannot reach an opaque provider-owned executor.

The supported compatibility matrix is JVM-only. LangChain4j 1.18 cancellation is adapted reflectively to keep the starter compatible across API versions; AOT/native-image support is deliberately not claimed until runtime hints and a native test matrix exist. Reflection failure is nonfatal and leaves the provider callback path operational.

---

## 20. HTTPS-only standalone transport

Standalone mode accepts only absolute HTTP(S) host URIs with a network host and without user-info, query, or fragment components. HTTPS is mandatory by default. Plaintext HTTP requires the deliberately named development-only builder or Spring property opt-in.

**Why:** Standalone OTLP authentication uses a Basic `Authorization` header. Accepting an accidental plaintext, credential-bearing, or ambiguous URI would expose credentials or route traces somewhere other than the configured origin. A loopback `HttpServer` contract test covers the final endpoint path, authentication and ingestion headers, serialized span name, and cross-origin redirect credential stripping.

---

## 21. Completion-aware annotated observations

`@ObserveGeneration` uses a raw span rather than `LangfuseGeneration` for asynchronous results. The observation is current during the synchronous method body only; Reactor results additionally restore it for source subscription and tasks scheduled while the active per-subscription lease context is current. No thread-affine Scope is held across the asynchronous lifetime. A `CompletionStage` attaches a side-effect callback and returns the original stage unchanged; Reactor creates one observation per subscription and ends it on complete, error, or cancel with an atomic terminal guard.

On framework model beans, an explicit model-method annotation has bean-level precedence over automatic model proxy instrumentation so the two mechanisms never emit nested duplicate generation spans.

**Why:** Moving a `LangfuseGeneration.end()` call to an arbitrary callback thread would close the Scope created on the invocation thread and corrupt both threads' context stacks. Raw spans separate observation lifetime from thread-affine Scope lifetime. Per-subscription Reactor state also prevents orphan spans for publishers that are never subscribed.
