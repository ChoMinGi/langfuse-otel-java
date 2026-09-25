# Explicit observation consumer (Java 11+)

Uses the packaged core's public API for synchronous and asynchronous calls without Spring or a
model-provider dependency. Tests verify a real application-owned OTel SDK with an in-memory exporter.

From the repository root, using Java 17+ for the reactor:

```sh
./mvnw -B -ntp -DskipTests -Djacoco.skip=true install
./mvnw -B -ntp -f consumer-tests/core-observation-consumer/pom.xml verify
```

The consumer itself supports Java 11. Change `JAVA_HOME` before the second command to test that runtime.
No live credentials or paid model calls are used. See [the API guide](../../OBSERVATION-API.md) for the
Scope, cancellation, privacy and automatic-instrumentation boundaries.
