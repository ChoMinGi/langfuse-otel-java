# See your first Java trace in Langfuse

This standalone example uses **langfuse-otel-core 0.2.3 from Maven Central**. It sends a fixed
question and answer as a synthetic workflow, then reads the observations back from Langfuse.
It makes no model calls and needs no provider API key. Langfuse receives three observations per run.

```text
java-first-trace       CHAIN
├── lookup-docs        TOOL
└── answer-question    GENERATION (model: demo-fixture)
```

## Run it

You need a JDK **11 or newer**, Git, internet access to Maven Central, and a Langfuse project
with **Observations API v2**. Langfuse Cloud supports this; the reproducible self-hosted check
uses Langfuse **4.41.0**. Maven is downloaded by the wrapper; you do not need to install the library locally.

1. Create or open a Langfuse project. In **Settings → API Keys**, create a project key pair.
2. Clone this repository and enter its root directory:

   ```bash
   git clone https://github.com/ChoMinGi/langfuse-otel-java.git
   cd langfuse-otel-java
   ```

3. Set the project host and keys, then run:

   ```bash
   export LANGFUSE_HOST="https://cloud.langfuse.com"
   export LANGFUSE_PUBLIC_KEY="pk-lf-..."
   export LANGFUSE_SECRET_KEY="sk-lf-..."
   ./mvnw -B -ntp -f examples/first-trace/pom.xml compile exec:java
   ```

   On Windows PowerShell:

   ```powershell
   $env:LANGFUSE_HOST = "https://cloud.langfuse.com"
   $env:LANGFUSE_PUBLIC_KEY = "pk-lf-..."
   $env:LANGFUSE_SECRET_KEY = "sk-lf-..."
   .\mvnw.cmd -B -ntp -f examples/first-trace/pom.xml compile exec:java
   ```

Choose the host where you created the project:

| Project location | `LANGFUSE_HOST` |
| --- | --- |
| Cloud EU | `https://cloud.langfuse.com` |
| Cloud US | `https://us.cloud.langfuse.com` |
| Cloud Japan | `https://jp.cloud.langfuse.com` |
| Self-hosted | Your HTTPS origin, without `/api` or another path |

The [official OTel integration guide](https://langfuse.com/integrations/native/opentelemetry)
lists endpoints. The library adds the OTLP/HTTP path and v4 ingestion header.

## Know when it worked

After dependency downloads, the application prints a trace ID and polls for about 90 seconds.
It exits successfully only after checking all three observations, their parent links, shared
metadata, fixture model label, and input/output content. A successful `flush()` alone is not enough.

```text
Sent synthetic trace: <trace-id>
Waiting up to 90 seconds for Langfuse ingestion...
Verified 3 observations: CHAIN -> TOOL + GENERATION
Verified parent links, synthetic input/output, and shared metadata.
Open: https://<your-host>/project/<your-project>/traces/<trace-id>
Read-back evidence: .../examples/first-trace/target/first-trace.json
```

Open the printed URL while signed into the same Langfuse project. You should see `java-first-trace`
with `lookup-docs` and `answer-question` as children. The demo labels every observation
`synthetic-no-model-call`; it does not fabricate tokens, cost, or first-token timing.

## Use it in your application

[FirstTrace.java](src/main/java/com/example/FirstTrace.java) contains the tracing example;
[ReadBack.java](src/main/java/com/example/ReadBack.java) is only the demonstration's ingestion check.
You do not need a read-back request for every production request.

- Replace the tool and generation fixture bodies with your application calls. Record provider
  model/usage values only when the provider supplies them.
- The demo explicitly enables `ContentCapturePolicy.captureAll()` for fixed public strings.
  Choose your own policy before recording application input or output. The default is metadata-only.
- Each observation closes independently; `.parent(root.context())` links children without keeping
  a thread-local scope open. For async work, completion, cancellation, and errors, see
  [the observation guide](../../OBSERVATION-API.md).
- Keep one `LangfuseOtel` instance for the application's lifetime. This short-lived CLI flushes
  and closes its owned SDK at the end. If your application already owns OTel, follow the
  [external SDK setup](../../README.md#existing-opentelemetry-sdk-production-recommended).
- When adding a manual generation around a model call, disable automatic model instrumentation
  for that call to avoid double-counting. See [migration guidance](../../MIGRATION-0.2.md).

## If it gets stuck

| Symptom | Next check |
| --- | --- |
| `Set LANGFUSE_...` | Set all three environment variables in the same terminal that runs Maven. |
| Java/compilation error | `java -version` and `./mvnw -version` must select JDK 11+. |
| Dependency resolution failed | Check network/proxy access to Maven Central. Retry the command with `-U` if a failed download was cached. |
| Read-back HTTP 401/403 | Use project public/secret keys, and the matching Cloud region or self-hosted origin. |
| Read-back HTTP 404 or redirect | Remove `/api` from the host and confirm the server exposes Observations API v2. |
| Certificate or HTTP error | Use a trusted HTTPS endpoint. Direct plaintext Docker export has a known [JDK sender issue](https://github.com/ChoMinGi/langfuse-otel-java/issues/9); use a TLS proxy. |
| Ingestion timeout / 0–2 observations | Search the printed trace ID in your project; inspect exporter errors and receiver/worker health. Do not treat the flush as proof of ingestion. |
| Verification succeeds but the UI cannot open | Sign into the same project. Search its traces for the printed trace ID if your UI version uses a different URL. |

Still blocked, or did it work? [Share your installation result](https://github.com/ChoMinGi/langfuse-otel-java/issues/new?template=first_trace_feedback.yml).
English and Korean are welcome. The form asks for environment, outcome, and the confusing step;
never paste API keys, private prompts, or customer data into an issue.

## Maintainer verification

CI compiles and tests this published-artifact consumer on Java 11, 17, and 21. The
[observation read-back workflow](../../.github/workflows/observation-e2e.yml) runs the same example
against the digest-pinned, disposable Langfuse 4.41.0 stack, with a fresh Maven repository.
The public version in this POM is intentional: update it after publication, independently of the
reactor's development version. Ordinary installation requires neither Docker nor Node.

To regenerate the documentation screenshot, manually run **Observation v4 read-back** with
`capture_screenshot=true`. It installs the locked browser helper, signs into only the disposable
project, captures the trace page, and uploads the PNG alongside the JSON read-back. Inspect the
image before copying it into `docs/images/`; the workflow never commits documentation automatically.
