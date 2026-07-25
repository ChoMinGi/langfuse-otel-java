# Release Runbook

This project deliberately separates release-candidate validation from public publication. The release workflow uploads exactly one signed candidate with Maven Central `autoPublish=false`, waits for `VALIDATED`, and creates a draft GitHub release. A maintainer must verify and publish both systems manually.

## One-time repository setup

1. Create a protected GitHub environment named `central-validation`.
2. Add required reviewers and restrict deployment branches/tags to the release policy.
3. Prefer environment-scoped `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY`, and `GPG_PASSPHRASE` secrets.
4. Protect `main` and release tags. Require the quality, `verify`, supported Java/framework
   matrix, and consumer checks on `main`.
5. Set the repository variable `LANGFUSE_INTEGRATION_ENABLED=true` only when maintained live-export credentials are available. When enabled, incomplete credentials fail CI; when disabled, CI records that the export smoke was not run. This smoke exercises export and flush but does not perform Langfuse ingestion read-back.

GitHub creates an unprotected environment automatically when a workflow first references an unknown name. Confirm the protection rules before pushing a release tag.

## Prepare the release commit

Use `0.2.0` below as an example. Prepare these changes as one commit on a release branch, then
merge it through the normal `main` protections:

1. Change the root and both module parent versions from `0.2.0-SNAPSHOT` to `0.2.0`.
2. Change `langfuse-otel.version` in both example POMs and all
   `consumer-tests` project POMs to `0.2.0`.
3. Replace the README release-candidate block with final release wording and confirm both dependency
   snippets use `0.2.0`.
4. Replace `Unreleased` in the `## [0.2.0]` changelog heading with the actual `YYYY-MM-DD` release
   date. Add a separate empty `## [Unreleased]` section above it for future work.
5. Set `project.build.outputTimestamp` once to a stable UTC timestamp for the release commit. Do not derive it from the current build time.
6. Review `SECURITY.md`, compatibility claims, and migration notes for the release version. Change
   the supported-version table from release-candidate status to public maintenance.

Run the Maven gates locally:

```bash
./mvnw -B -ntp clean verify
./mvnw -B -ntp -Pquality -DskipTests -Djacoco.skip=true -Dmaven.javadoc.skip=true clean verify
./mvnw -B -ntp -DskipTests -Djacoco.skip=true install
./mvnw -B -ntp -f examples/spring-ai-example/pom.xml -DskipTests verify
./mvnw -B -ntp -f examples/langchain4j-example/pom.xml -DskipTests verify
./mvnw -B -ntp -f consumer-tests/spring-boot-consumer/pom.xml verify
./mvnw -B -ntp -f consumer-tests/spring-boot-consumer/pom.xml -Dspring-ai.version=1.1.8 clean verify
./mvnw -B -ntp -f consumer-tests/langchain4j-spring-boot-consumer/pom.xml verify
./mvnw -B -ntp -f consumer-tests/langchain4j-spring-boot-consumer/pom.xml -Dlangchain4j.version=1.18.0 clean verify
./mvnw -B -ntp -f consumer-tests/core-prompt-consumer/pom.xml verify
```

The release workflow also scans the generated SBOM and rejects High or Critical vulnerability
findings.

Merge the release commit into `main` and wait for required main CI checks.

## Verify the exact release commit in Langfuse

Run the release canary from a clean checkout of the same commit now at `origin/main`. It exports one
root, one generic child, and one generation with unique I/O and trace-wide fields. The canary uses
strict construction and fails unless the local export and flush both succeed.

```bash
git fetch origin main
test -z "$(git status --porcelain)"
release_sha="$(git rev-parse HEAD)"
test "$release_sha" = "$(git rev-parse origin/main)"
release_short_sha="$(git rev-parse --short=12 HEAD)"

export LANGFUSE_CANARY_COMMIT="$release_sha"
LANGFUSE_CANARY_MARKER="v0.2.0-${release_short_sha}-$(date -u +%Y%m%dT%H%M%SZ)"
export LANGFUSE_CANARY_MARKER
canary_from="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"

./mvnw -B -ntp -pl langfuse-otel-core -am test \
  -Dtest.excludedGroups= \
  -Dgroups=release-canary \
  -DfailIfNoTests=true \
  -Dtest=LangfuseV4CanaryIntegrationTest \
  -Dlangfuse.canary.release=0.2.0

canary_to="$(date -u +%Y-%m-%dT%H:%M:%S.999Z)"
canary_name="langfuse-otel-java-v4-canary-${LANGFUSE_CANARY_MARKER}"
```

`LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` must already be present in the environment;
`LANGFUSE_HOST` is optional. Do not enable shell tracing while credentials are set.

Read the root back through Observations API v2, then fetch all rows by its `traceId`. The temporary
curl configuration keeps credentials out of the command line:

```bash
canary_host="${LANGFUSE_HOST:-https://cloud.langfuse.com}"
canary_host="${canary_host%/}"
canary_curl_config="$(mktemp)"
canary_root_json="$(mktemp)"
canary_trace_json="$(mktemp)"
chmod 600 "$canary_curl_config" "$canary_root_json" "$canary_trace_json"
trap 'rm -f "$canary_curl_config" "$canary_root_json" "$canary_trace_json"' EXIT
printf 'user = "%s:%s"\n' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY" > "$canary_curl_config"

trace_id=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if curl --config "$canary_curl_config" --fail --silent --show-error --get \
      "${canary_host}/api/public/v2/observations" \
      --data-urlencode "name=${canary_name}" \
      --data-urlencode "fields=core,basic,io,metadata,trace_context" \
      --data-urlencode "fromStartTime=${canary_from}" \
      --data-urlencode "toStartTime=${canary_to}" \
      --data-urlencode "limit=10" > "$canary_root_json"; then
    trace_id="$(jq -r 'if (.data | length) == 1 then .data[0].traceId else empty end' \
      "$canary_root_json")"
  fi
  test -n "$trace_id" && break
  sleep 5
done
test -n "$trace_id"

canary_child="${canary_name}-child"
canary_generation="${canary_name}-generation"
verify_canary() {
  jq -e \
    --arg root "$canary_name" \
    --arg child "$canary_child" \
    --arg generation "$canary_generation" \
    --arg marker "$LANGFUSE_CANARY_MARKER" \
    --arg sha "$LANGFUSE_CANARY_COMMIT" \
    --arg release "0.2.0" '
      def row($name): .data[] | select(.name == $name);
      (row($root)) as $root_row |
      ((.data | length) == 3) and
      ((.meta.cursor // null) == null) and
      (([.data[].name] | sort) == ([$root, $child, $generation] | sort)) and
      ($root_row.type == "SPAN") and
      ($root_row.parentObservationId == null) and
      ($root_row.input == ("root-input-" + $marker)) and
      ($root_row.output == ("root-output-" + $marker)) and
      ((row($child).type == "SPAN") and
        (row($child).parentObservationId == $root_row.id) and
        (row($child).input == ("child-input-" + $marker)) and
        (row($child).output == ("child-output-" + $marker))) and
      ((row($generation).type == "GENERATION") and
        (row($generation).parentObservationId == $root_row.id) and
        (row($generation).input == ("generation-input-" + $marker)) and
        (row($generation).output == ("generation-output-" + $marker))) and
      ([.data[].userId] | all(. == ("canary-user-" + $marker))) and
      ([.data[].sessionId] | all(. == ("canary-session-" + $marker))) and
      ([.data[].version] | all(. == $sha)) and
      ([.data[].release] | all(. == $release)) and
      ([.data[].environment] | all(. == "release-canary")) and
      ([.data[].traceName] | all(. == $root)) and
      ([.data[].tags] | all((sort) == (["release-canary", $marker] | sort))) and
      ([.data[].metadata.canary_marker] | all(. == $marker))
    ' "$canary_trace_json"
}

for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if curl --config "$canary_curl_config" --fail --silent --show-error --get \
      "${canary_host}/api/public/v2/observations" \
      --data-urlencode "traceId=${trace_id}" \
      --data-urlencode "fields=core,basic,io,metadata,trace_context" \
      --data-urlencode "fromStartTime=${canary_from}" \
      --data-urlencode "toStartTime=${canary_to}" \
      --data-urlencode "limit=10" > "$canary_trace_json" &&
      verify_canary > /dev/null; then
    break
  fi
  sleep 5
done
verify_canary
```

Retain the `traceId` and canary marker in the release audit record. Any failed assertion blocks the
tag.

Create a signed annotated tag only after this read-back succeeds. The signing key must be
registered with GitHub so the tag signature is reported as verified:

```bash
git fetch origin main
test "$(git rev-parse HEAD)" = "$release_sha"
test "$(git rev-parse origin/main)" = "$release_sha"
git tag -s -m "Release 0.2.0" v0.2.0 "$release_sha"
git push origin v0.2.0
```

## What the tag workflow enforces

Before Central credentials are available, the workflow verifies:

- an annotated release tag with a GitHub-verified signature, root/module/example versions, release commit, and `main` ancestry;
- release-version snippets in README and a dated changelog heading;
- absence of an existing GitHub release or published Maven Central coordinates;
- `clean verify`, including coverage, warning-free Javadocs, and binary/source compatibility with
  `0.1.1`;
- SpotBugs, dependency-license, CycloneDX SBOM, and High/Critical vulnerability gates;
- core Java 11/17/21, the blocking Spring AI/LangChain4j matrix, and all consumer checks.

The `central-validation` environment is entered only after every gate succeeds. The deploy job builds and signs once, uploads a candidate named `io.github.chomingi:langfuse-otel-java:<version>`, waits for `VALIDATED`, and stops. The following job has the only `contents:write` permission and creates a draft GitHub release without checking out or building repository code.

## Inspect and publish the Central candidate

1. Open [Central Publisher Portal deployments](https://central.sonatype.com/publishing/deployments).
2. Locate `io.github.chomingi:langfuse-otel-java:<version>` and record its deployment ID in the release issue or audit record.
3. Require state `VALIDATED`. Inspect the listed POM, JAR, sources, Javadoc, SBOM, checksums, and
   signatures. Drop the candidate instead of publishing if any coordinate or metadata is wrong.
4. Optionally resolve the validated candidate through Central's manual-testing repository as described in the [Publisher API documentation](https://central.sonatype.org/publish/publish-portal-api/#manually-testing-a-deployment-bundle).
5. Select **Publish** in the Portal. Wait until the deployment state is `PUBLISHED`.

`VALIDATED` is not a public release. Do not publish the GitHub draft while Central still reports `VALIDATED` or `PUBLISHING`.

## Verify public resolution

Avoid a warm local Maven cache. Resolve both public artifacts into a new temporary repository, then
run the two framework consumer projects against that same repository:

```bash
release_m2="$(mktemp -d)"
./mvnw -B -ntp -U -Dmaven.repo.local="$release_m2" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=io.github.chomingi:langfuse-otel-core:0.2.0
./mvnw -B -ntp -U -Dmaven.repo.local="$release_m2" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=io.github.chomingi:langfuse-otel-spring-boot-starter:0.2.0
./mvnw -B -ntp -U -Dmaven.repo.local="$release_m2" -f consumer-tests/spring-boot-consumer/pom.xml clean verify
./mvnw -B -ntp -U -Dmaven.repo.local="$release_m2" -f consumer-tests/langchain4j-spring-boot-consumer/pom.xml clean verify
```

Do not install the reactor into `release_m2`; these runs must consume the artifacts published by
Central. They start non-web Spring Boot applications, invoke Spring AI and LangChain4j chat models,
and verify both application-owned spans and standalone OTLP requests. Central synchronization can
take time. Retry the commands without rebuilding or re-uploading the release, and retain the
temporary repository until they succeed so its contents can be inspected if necessary.

## Publish the GitHub draft

After both artifacts resolve publicly, review the draft title and changelog link, then publish it:

```bash
gh release view v0.2.0 --json isDraft,url
gh release edit v0.2.0 --draft=false --latest
```

Finally, move `main` to the next development version, update the example and consumer-test
`langfuse-otel.version` properties, keep the empty changelog `Unreleased` section created by the
release commit, and advance `api.compatibility.baseline` to the released version. Keep the README
Quick Start dependency snippets on the latest version available from Maven Central. If the README
also mentions the development version, label it separately as unpublished and require a local
install.

## Failure and retry policy

- Never use **Re-run all jobs** to retry a Central upload. The deploy job rejects every workflow `run_attempt` after the first.
- If only draft creation failed after Central validation, use **Re-run failed jobs**. The already-successful deploy job is not run again, and draft creation is idempotent.
- If a gate or Central upload failed, inspect Central Portal first. If no deployment exists, start a new manual `workflow_dispatch` run for the same existing tag and explicitly confirm `confirm_no_existing_deployment`.
- If a candidate exists in `VALIDATED` or `FAILED`, publish or drop that candidate deliberately. Do not create a second candidate for the same tag.
- If the candidate contents are wrong, drop it and prepare a new version and immutable tag. Do not move or replace a release tag that others may already have fetched.
