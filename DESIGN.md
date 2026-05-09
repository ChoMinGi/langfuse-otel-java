# Design Decisions

Key architectural and implementation decisions for langfuse-otel-java.

---

## 1. OTel-based, not direct Langfuse API

We wrap OpenTelemetry SDK instead of calling the Langfuse ingestion API directly.

**Why:** Langfuse's maintainer explicitly recommends OTel for Java tracing ([langfuse-java #2](https://github.com/langfuse/langfuse-java/issues/2#issuecomment-2706738123), [#24](https://github.com/langfuse/langfuse-java/issues/24#issuecomment-2698403123)). OTel is vendor-neutral — users can send traces to Langfuse and other backends (Datadog, Jaeger) simultaneously. The Langfuse OTLP endpoint handles `gen_ai.*` semantic conventions natively, so we don't need to reinvent trace data modeling.

**Alternative considered:** Direct API calls (like [yuvenhol/langfuse_java](https://github.com/yuvenhol/langfuse_java)). Rejected because it creates Langfuse lock-in and duplicates what OTel already does well.

---

## 2. Three API styles (callback, try-with-resources, manual end)

```java
// Callback
langfuse.trace("flow", trace -> { ... });

// Try-with-resources
try (LangfuseTrace trace = langfuse.trace("flow")) { ... }

// Manual
LangfuseTrace trace = langfuse.trace("flow");
trace.end();
```

**Why:** Different Java codebases have different styles. Callback is cleanest for new code. Try-with-resources is idiomatic Java. Manual `end()` is necessary for async flows where scope doesn't align with method boundaries. Forcing one style on an open-source library limits adoption.

**Trade-off:** Three patterns means more API surface to maintain and test. We accept this because the implementation cost is low (callback just wraps TWR internally) and the user benefit is high.

---

## 3. failSafe defaults to true (no-op on missing keys)

```java
LangfuseOtel.builder().build()  // no keys → returns no-op, never throws
```

**Why:** An observability library must never crash the host application. If API keys are misconfigured, the app should run normally — just without tracing. This is critical for production safety. Users who want strict validation can use `.failSafe(false)`.

**Precedent:** Honeycomb OTel Java SDK, Datadog Java tracer — both silently degrade when misconfigured.

---

## 4. SpanGuard with java.lang.ref.Cleaner

When a span is not properly closed, `SpanGuard` logs a WARNING and closes the span via GC finalization.

**Why:** Forgetting to close a span (especially with the manual `end()` pattern) is a common mistake. Without SpanGuard, the span stays open forever on the OTel context stack, silently corrupting parent-child relationships for all subsequent spans on that thread. The Cleaner-based approach provides a safety net during development without runtime overhead in the normal (properly closed) path.

**Limitation:** GC-based cleanup is unreliable — the Cleaner may not fire before JVM shutdown. This is acceptable because SpanGuard is a safety net, not the primary mechanism. The real fix is the WARNING log that tells the developer to use `try-with-resources` or `end()`.

---

## 5. AtomicBoolean for close() guard

```java
private final AtomicBoolean closed = new AtomicBoolean(false);

public void close() {
    if (closed.compareAndSet(false, true)) {
        cleanable.clean();
    }
}
```

**Why:** `close()` delegates to `cleanable.clean()` which calls `scope.close()` + `span.end()`. Calling `scope.close()` twice corrupts the OTel context stack. While the JDK Cleaner guarantees at-most-once execution, `AtomicBoolean` provides an additional thread-safe guard for the case where `close()` and the Cleaner daemon run concurrently.

**Why not volatile boolean:** `if (!closed) { closed = true; }` is a check-then-act race. Two threads can both see `closed == false` and both enter the block.

---

## 6. close() delegates entirely to cleanable.clean()

```java
public void close() {
    if (closed.compareAndSet(false, true)) {
        cleanable.clean();  // this does scope.close() + span.end()
        // NOT: scope.close(); span.end(); — would cause double-close
    }
}
```

**Why:** Earlier versions called `cleanable.clean()` AND `scope.close()` + `span.end()` in `close()`. This caused double-close because `clean()` already invokes `CleanAction.run()` which does `scope.close()` + `span.end()`. Double `scope.close()` corrupts the OTel context stack by popping the wrong parent context.

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

## 10. Spring AI pointcut: call(..) not call(Prompt)

```java
@Around("execution(* org.springframework.ai.chat.model.ChatModel.call(..))")
```

**Why:** Spring AI's `ChatModel` has overloads: `call(Prompt)`, `call(String)`, `call(Message...)`. Targeting only `call(Prompt)` misses the simpler overloads that many tutorials and examples use. Using `call(..)` catches all variants. The aspect checks `instanceof` on the argument to extract attributes appropriately.

---

## 11. LangfuseGeneration constructor is public

Normally, `LangfuseGeneration` should only be created via `trace.generation("name")`. The constructor is public because Spring AOP aspects (in the separate `spring-boot-starter` module) need to create instances directly: `new LangfuseGeneration(tracer, name)`.

---

## 12. Object type for langfuseClient

```java
public Builder langfuseClient(Object langfuseClient) { ... }
public LangfusePromptHelper prompt(Object langfuseClient, String promptName) { ... }
```

**Why:** `com.langfuse:langfuse-java` is an optional dependency. Using the concrete type `LangfuseClient` in the public API would cause `NoClassDefFoundError` for users who don't have it on their classpath — even if they never call `prompt()`. Using `Object` defers the class loading to the point of use, where we guard it with `Class.forName()` check.

**Trade-off:** No compile-time type safety. Users can pass any object and get a `ClassCastException`. This is acceptable because the prompt API is an advanced feature, and the error message is clear.

---

## 13. Raw OTel Span for streaming (no makeCurrent)

Streaming tracing wrappers (`TracingSpringAiChatModel.stream()`, `TracingStreamingLangChain4jChatModel`) use the raw OTel `Span` API instead of `LangfuseGeneration`.

```java
Span span = tracer.spanBuilder(name)
    .setParent(Context.current())  // capture parent on calling thread
    .setSpanKind(SpanKind.CLIENT)
    .startSpan();
// NO span.makeCurrent() — no Scope created
```

**Why:** `AbstractLangfuseSpan` calls `span.makeCurrent()` in its constructor, pushing the span onto the thread-local OTel context stack. This is correct for synchronous flows where the span opens and closes on the same thread. For streaming, responses arrive on different threads (Reactor schedulers for Spring AI's `Flux`, callback threads for LangChain4j's `StreamingChatResponseHandler`). Calling `makeCurrent()` on the originating thread and `scope.close()` on a callback thread corrupts the context stack.

**How it works:** The span is created with `setParent(Context.current())` to preserve the parent-child relationship from the calling thread. Attributes are set directly via `span.setAttribute(...)`. The span is ended in terminal signals (`doOnComplete`/`doOnError`/`doOnCancel` for Flux, `onCompleteResponse`/`onError` for callbacks). An `AtomicBoolean` guard prevents double-end from concurrent terminal signals.

**Trade-off:** Some code duplication — the attribute-setting logic from `LangfuseGeneration` is replicated as helper methods in the streaming wrappers, since `LangfuseGeneration` cannot be used without `makeCurrent()`. This is acceptable because extracting a shared utility would require modifying the core module for a starter-only concern.

---

## 14. BeanPostProcessor over AOP for auto-instrumentation

The starter uses `BeanPostProcessor` (Decorator pattern) instead of AOP aspects to wrap model beans.

```java
class SpringAiChatModelBeanPostProcessor implements BeanPostProcessor {
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ChatModel) {
            return new TracingSpringAiChatModel((ChatModel) bean, langfuseOtel);
        }
        return bean;
    }
}
```

**Why:** Earlier versions used `@Aspect` classes with `@Around` pointcuts on `ChatModel.call()`. This had two problems: (1) aspects must be registered as Spring beans to be active, but the auto-configurations never registered them — making them dead code; (2) if both the BeanPostProcessor wrapper and an AOP aspect were active simultaneously, double tracing would occur because the aspect would intercept calls on the wrapper too.

**Alternative considered:** Removing the BeanPostProcessor and using only AOP. Rejected because AOP requires Spring AOP proxying to be active, and pointcut matching on interface methods can be unreliable with CGLIB proxies. The BeanPostProcessor approach is simpler, more predictable, and works with any bean regardless of proxy type.
