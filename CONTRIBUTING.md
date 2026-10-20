# Contributing

Bug reports, installation feedback, documentation fixes, and code contributions are welcome.

## Development setup

Use Java 17 or newer. The Maven Wrapper pins the Maven version used by CI, so a system Maven
installation is not required. On Windows, replace `./mvnw` with `mvnw.cmd`.

For a fast unit-test run:

```bash
./mvnw -B -ntp test
```

Before opening a pull request:

```bash
./mvnw -B -ntp clean verify
```

`clean verify` runs unit and packaged-JAR tests, coverage checks, warning-free Javadocs, and
binary/source API compatibility against the `api.compatibility.baseline` in [pom.xml](pom.xml)
(currently `0.2.3`). Coverage reports are written under each module's
`target/site/jacoco` directory.

The projects under `consumer-tests` build separately from the library. After changing
framework adapters, auto-configuration, or dependency metadata, install the reactor artifacts and
run the framework, prompt, and observation consumers:

```bash
./mvnw -B -ntp -DskipTests -Djacoco.skip=true install
./mvnw -B -ntp -f consumer-tests/spring-boot-consumer/pom.xml verify
./mvnw -B -ntp -f consumer-tests/langchain4j-spring-boot-consumer/pom.xml verify
./mvnw -B -ntp -f consumer-tests/core-prompt-consumer/pom.xml verify
./mvnw -B -ntp -f consumer-tests/core-observation-consumer/pom.xml verify
```

The full baseline/latest framework commands used for a release are listed in
[RELEASING.md](RELEASING.md#prepare-the-release-commit).

## Langfuse integration checks

For the observation API and published first-trace example, run the same Langfuse 4.41.0 check
used by CI:

```bash
bash experiments/core-preflight/docker/run.sh
```

It uses a disposable Langfuse stack, project keys, and a TLS proxy. Prerequisites and cleanup
details are in the [runner guide](experiments/core-preflight/README.md).

The older framework check covers Spring AI and LangChain4j model beans:

```bash
./e2e/langfuse-docker/run.sh
```

This starts a digest-pinned Langfuse `v4.0.0-rc.2` stack with disposable project keys and a local
TLS proxy, then runs the Spring AI and LangChain4j consumer tests. It needs Docker, Java 17,
`curl`, `jq`, `openssl`, and `keytool`, but no Langfuse Cloud credentials. The stack uses an
isolated Compose project and is removed with its volumes when the command exits.

## Other checks

The quality profile adds static analysis, dependency-license checks, and a CycloneDX SBOM:

```bash
./mvnw -B -ntp -Pquality -DskipTests -Djacoco.skip=true \
  -Dmaven.javadoc.skip=true clean verify
```

Live integration tests require Langfuse credentials:

```bash
export LANGFUSE_PUBLIC_KEY=pk-lf-...
export LANGFUSE_SECRET_KEY=sk-lf-...
export LANGFUSE_HOST=https://cloud.langfuse.com

./mvnw -B -ntp test -pl langfuse-otel-core -am \
  -Dtest.excludedGroups= -Dgroups=integration -DfailIfNoTests=true
```

CI runs live export tests only when `LANGFUSE_INTEGRATION_ENABLED=true`. Once enabled, missing
credentials fail the integration status job instead of silently skipping it.

## Repository layout

| Path | Purpose |
|------|---------|
| `langfuse-otel-core` | Framework-neutral Java 11 tracing API and standalone exporter |
| `langfuse-otel-spring-boot-starter` | Java 17 Spring AI and LangChain4j auto-configuration |
| `examples` | First-trace application and Spring AI/LangChain4j examples |
| `consumer-tests` | Independent user-flow builds outside the reactor |
| `experiments/core-preflight` | Observation contract checks and Langfuse 4.41.0 read-back |
| `experiments/core-operations` | Export failure, queue, retention, and load checks |
| `e2e/langfuse-docker` | Framework model read-back against Langfuse 4.0.0-rc.2 |

## Guidelines

- Open an issue before starting a new feature.
- Keep pull requests focused and add tests for changed behavior.
- Keep the core module compatible with Java 11 and the starter with Java 17.
- Follow the existing style: no Lombok and no comments that repeat the code.
- Do not commit credentials or captured model data.

## Adding 0.2.x auto-instrumentation

The current Boot 3 adapter line uses tracing decorators and type-preserving Spring proxies:

1. Add a `Tracing*Model` decorator for the framework model interface.
2. Register it through the appropriate auto-configuration and shared model post-processor.
3. Keep framework dependencies optional.
4. Preserve provider extension methods and concrete injection where proxying is safe.
5. Cover success, setup failure, delegate failure, and any asynchronous terminal paths.

Streaming adapters create state per subscription or invocation and use raw OpenTelemetry spans
with short-lived scopes at callback boundaries. See the
[asynchronous lifecycle design](DESIGN.md#asynchronous-observation-and-context-lifecycle).

## Releasing

Maintainers must follow [RELEASING.md](RELEASING.md). A Maven Central `VALIDATED` candidate is not
public, and a Central upload job must not be rerun before checking the Publisher Portal.
