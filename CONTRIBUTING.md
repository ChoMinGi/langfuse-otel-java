# Contributing

Thanks for contributing to langfuse-otel-java.

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
binary/source API compatibility against `0.1.1`. Coverage reports are written under each module's
`target/site/jacoco` directory.

The slower quality profile adds static analysis and dependency-license checks while producing the
CycloneDX SBOM used by CI:

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
  -DexcludedGroups= -Dgroups=integration
```

CI runs live export tests only when `LANGFUSE_INTEGRATION_ENABLED=true`. Once enabled, missing
credentials fail the integration status job instead of silently skipping it.

## Repository layout

| Path | Purpose |
|------|---------|
| `langfuse-otel-core` | Framework-neutral Java 11 tracing API and standalone exporter |
| `langfuse-otel-spring-boot-starter` | Java 17 Spring AI and LangChain4j auto-configuration |
| `examples` | Consumer builds for both supported adapters |

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
