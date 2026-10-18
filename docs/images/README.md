# First-trace screenshot provenance

`first-trace.png` is an unmodified browser capture of a real, disposable Langfuse **4.41.0**
instance. It contains only fixed synthetic content from the published-core example, not a mock UI
or an external user's installation. The project and its random credentials were destroyed by the runner.

- Captured: **2026-10-18 14:44 UTC**.
- Source commit: `f94670bba155366bda461869acf5254d371f1f61`.
- [Successful read-back and capture run](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/39137124953).
- [Java 11/17/21 and regression CI](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/39137126966).
- Consumer: **langfuse-otel-core 0.2.3**, downloaded from Maven Central into a fresh repository.
- Core JAR SHA-256: `b65f4af17b09c3d3f7814dbe6f25e420adc9702f3d7c5f8b7ea8aa14c7ff16ee`
  (independently checked from the local cold-cache download).
- OTel SDK: **1.66.0**, also present in the captured observation metadata.
- Trace ID: `2215fd187cd940d26ce6d91571ec9a86`.
- [Complete Observations API v2 response data](first-trace.json): three observations with checked
  parent links, input/output, shared metadata, and a `demo-fixture` generation with no provider usage.
- The browser opened that same trace, waited for both child names and the root input, then captured
  a 1440 × 900 viewport. No credentials, real prompts, or customer data appear in the image or JSON.

The [Docker compose file](../../experiments/core-preflight/docker/compose.yaml) pins both the
Langfuse web and worker image digests. Its runner verifies revision
`d84020653a619a374bd36e4563d52a5db5590129` and cleans up its own containers and volumes.
This demonstrates this example on that version; it is not a claim of broad Cloud or self-hosted UI compatibility.

Regeneration instructions are in the [example guide](../../examples/first-trace/README.md#maintainer-verification).
