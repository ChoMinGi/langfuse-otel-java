# Production Roadmap

## 0.2.0 Production Preview

### Implemented in the current development line

- [x] Preserve the previous local history and base 0.2 work on the latest remote main
- [x] Align reactor versions at `0.2.0-SNAPSHOT`
- [x] Add a checksum-pinned Maven Wrapper
- [x] Produce bit-for-bit stable main, sources, and Javadoc JARs with a fixed build timestamp
- [x] Make `clean verify` (tests, package, sources, Javadoc) a required CI job
- [x] Validate release tag, POM version, and main ancestry
- [x] Stop Maven Central at validated state for manual publish approval
- [x] Support application-owned `OpenTelemetry` without duplicate SDK/exporter ownership
- [x] Add explicit `auto`, `external`, and `standalone` OTel selection with ambiguity checks
- [x] Default automatic content capture to metadata-only
- [x] Add per-direction opt-in, redaction callback, and post-redaction length limit
- [x] Default exception capture to type-only with separate redacted message/stack opt-ins
- [x] Keep stack-only capture free of throwable messages, including causes and suppressed exceptions
- [x] Make HTTP Principal and session ID export explicit opt-ins
- [x] Propagate immutable request metadata through OTel/Reactor Context
- [x] Isolate concurrent WebFlux request context across scheduler changes
- [x] Create Spring AI streaming state per subscription
- [x] Cover no-subscribe, re-subscribe, concurrent subscribe, error, cancel, and context cases
- [x] Bound enabled streaming-output accumulators and avoid allocating them in metadata-only mode
- [x] Preserve provider embedding-dimension and image-edit methods
- [x] Preserve proxyable provider concrete types and extension interfaces during automatic instrumentation
- [x] Keep final/non-proxyable model beans unchanged instead of substituting an incompatible decorator
- [x] Keep streaming-only LangChain4j beans out of synchronous `ChatModel` candidates in automatic BeanPostProcessor instrumentation
- [x] Make `@ObserveGeneration` completion-aware for Reactor and `CompletionStage` results
- [x] Give explicit model-method annotations bean-level precedence to prevent duplicate observations
- [x] Bridge the Spring AI source-subscription boundary into the wrapper observation context
- [x] Make synchronous provider spans children of raw wrapper observations
- [x] Promote one instrumented early model proxy across explicitly enabled singleton circular references
- [x] Carry wrapper observation context and immutable metadata across Reactor Scheduler hops with a per-subscription, terminal-bound lease
- [x] Restore raw Reactive Streams signals, request, and cancel, and expose a per-subscription source-boundary bridge for provider-side operators
- [x] Restore LangChain4j streaming callbacks with the captured invocation context and expose terminal-aware submission-time and fixed-snapshot executor bridges for provider-owned scheduling
- [x] Restore OTel Scope even when a raw span is ended before its wrapper
- [x] Compile consumer examples against the locally built snapshot in CI
- [x] Gate Central credentials behind release preflight, Java/framework matrices, and consumer builds
- [x] Add a manual Central publish and post-publication resolution runbook
- [x] Publish a 0.1.x to 0.2 migration guide for privacy and OTel ownership defaults

### Required before the 0.2.0 release

- [x] Add a mock OTLP receiver contract test for endpoint path, span payload/name, and auth headers
- [x] Extend the mock OTLP receiver contract to assert multi-span hierarchy and representative attributes
- [x] Reject insecure standalone endpoints unless an explicit development-only opt-in is set
- [x] Add redirect/credential-host safety tests for the standalone exporter
- [x] Add an explicit policy for exception messages and user metadata that may contain sensitive values
- [x] Bound pre-redaction streaming buffers and drop overflowing raw streams before redaction
- [x] Restore immutable request metadata at Spring AI/annotated Reactor subscription and LangChain4j callback boundaries
- [x] Preserve concrete/provider extension types or leave non-proxyable beans unchanged instead of substituting their model type
- [x] Stop exposing streaming-only LangChain4j models as synchronous `ChatModel` candidates in automatic BeanPostProcessor instrumentation
- [x] Make `@ObserveGeneration` completion-aware for Reactor and `CompletionStage` results
- [x] Propagate wrapper observation context into Reactor Scheduler tasks scheduled under the active instrumented subscription
- [x] Restore wrapper observation context around LangChain4j streaming callbacks and expose `LangChain4jStreamingContext` submission-time and late-scheduling snapshot bridges
- [x] Validate and document supported-provider executor configuration where LangChain4j implementations use an opaque internal executor; generic SPI coverage stops at the callback and explicit scheduling boundaries
- [x] Expose Actuator health/metrics for ownership mode, no-op fallback, exporter failures, queue drops, and flush state
- [x] Add JaCoCo baseline and prevent coverage regression
- [x] Align the Boot 3 dependency baseline and reject dependency convergence regressions
- [ ] Add static analysis, dependency vulnerability, license, and SBOM gates
- [ ] Add binary/source API compatibility checks against 0.1.1
- [ ] Resolve public API Javadoc warnings
- [ ] Run the Java/framework compatibility matrix on the release candidate
- [ ] Rehearse Central validation, manual publish, draft release publication, and post-publish resolution

### Required before 1.0 RC

- [ ] Verify trace/generation hierarchy and attributes through the real Langfuse API
- [ ] Run concurrency, exporter-outage, queue-saturation, shutdown, performance, and soak tests
- [ ] Record zero span/context leaks under sustained reactive and streaming load
- [x] Validate custom Publisher integrations that dispatch outside Reactor Scheduler hooks with `ReactorContextPropagation.wrap(...)`, including concurrent subscriptions, terminal races, and lease cleanup
- [ ] Add Spring AOT/GraalVM runtime hints and a native-image matrix before claiming native support for reflective adapter paths
- [ ] Freeze the supported Java, Spring Boot, Spring AI, and LangChain4j matrix
- [ ] Complete a threat model and external security review

## 0.3 Adapter Direction

- Move the Spring starter to Boot 4 and Spring AI 2 as a separate compatibility line
- Replace Spring AI model BeanPostProcessor wrappers with its Observation extension points where supported
- Prefer framework-native LangChain4j listener/context hooks over model proxying where supported, retaining an explicit provider scheduling boundary
- Centralize and version the GenAI semantic attribute mapping
- Split core API, framework adapters, and optional standalone SDK/autoconfigure responsibilities
