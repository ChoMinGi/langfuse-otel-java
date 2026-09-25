# Phase-0 core preflight

This directory contains the phase-0 probes against `33a8926` and the subsequent A/B live read-back.
It is not a published module. The historical decision is in [CORE-PREFLIGHT.md](../../CORE-PREFLIGHT.md);
the implemented API and its limits are in [OBSERVATION-API.md](../../OBSERVATION-API.md).
All payloads in this directory are synthetic. No model provider or existing Langfuse project is required.

## Reproduce

From the repository root, use JDK 17+ to install the current reactor and run its tests:

```sh
./mvnw -B -ntp -DskipTests -Djacoco.skip=true install
./mvnw -B -ntp -Djacoco.skip=true -Dmaven.javadoc.skip=true test
./mvnw -B -ntp -f experiments/core-preflight/pom.xml -Dtest=ExistingHelpersTest test
```

The independent consumer targets Java 11 and was also executed on a Java 11 runtime.
`RawOtelExamples` uses only existing public APIs. `ExistingHelpersTest` deliberately characterizes
current gaps as well as successful usage; a passing characterization is not proof that the new contract is implemented.

The two package-local probes in the normal modules need access to existing internal components:

- `CorePreflightSnapshotBoundaryTest`: frozen carrier feasibility using the real context processor.
- `CorePreflightMixingTest`: manual/automatic generation counts and token sums using Spring AI's existing instrumentation.

To run only those probes (JDK 17+):

```sh
./mvnw -B -ntp '-Dtest=CorePreflight*' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true test
```

## Disposable stable Langfuse read-back

Requires Docker Compose, curl, jq, openssl, a JDK/keytool, and locally installed reactor artifacts.

```sh
bash experiments/core-preflight/docker/run.sh
```

The runner creates a separate random Compose project, random credentials, a temporary CA/truststore,
and a loopback TLS endpoint. It removes only its own project/volumes and temporary certificate directory on exit.
It does not use an existing `.env` or hosted Langfuse credentials. Images may remain in Docker's local cache.
The Compose/TLS fixture is derived from `e2e/langfuse-docker` and intentionally isolated from the default CI's existing pins.
Promote or remove this fixture when adopting the design; do not maintain two permanent E2E stacks.

`LiveV4Test` is opt-in (`PREFLIGHT_LIVE=true`, set by the runner). It now verifies four traces with three observations each
(two from the original helpers and two from the new observation API),
including API v2 metadata filters and the absence of payloads when capture is disabled.
The endpoint rejects OTLP requests without ingestion version 4. API calls use bounded request/read-back timeouts.
The runner verifies version/revision image labels as well as pinning Docker digests.

`target/live-v4-*.json` and `target/live-observation-*.json` contain the last synthetic API responses.
The checked-in candidate files under `evidence/` preserve the reviewed run; rerunning does not overwrite those files.
Credentials, private keys and container logs are not included in the evidence directory.

The current live test compiles against the newly implemented observation API: install the current working-tree
artifacts first. The historical phase-0 evidence remains at `evidence/`; subsequent API evidence is in
`evidence/observation-api/`. The implementation lives in the normal core module, while these disposable
probes remain outside the default reactor. No release, performance benchmark or leak certification is included.

The Docker runner records the current Git commit and root Maven version in every new trace and
asserts both on read-back. Historical evidence retains its original pre-release provenance.
