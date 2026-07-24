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

Merge the release commit into `main` and wait for required main CI checks. Create a signed annotated tag only after that commit is present on `main`; the signing key must be registered with GitHub so the tag signature is reported as verified:

```bash
git tag -s v0.2.0 -m "Release 0.2.0"
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
`langfuse-otel.version` properties, restore the changelog's `Unreleased` section, and advance
`api.compatibility.baseline` to the released version. Keep the README Quick Start dependency
snippets on the latest version available from Maven Central. If the README also mentions the
development version, label it separately as unpublished and require a local install.

## Failure and retry policy

- Never use **Re-run all jobs** to retry a Central upload. The deploy job rejects every workflow `run_attempt` after the first.
- If only draft creation failed after Central validation, use **Re-run failed jobs**. The already-successful deploy job is not run again, and draft creation is idempotent.
- If a gate or Central upload failed, inspect Central Portal first. If no deployment exists, start a new manual `workflow_dispatch` run for the same existing tag and explicitly confirm `confirm_no_existing_deployment`.
- If a candidate exists in `VALIDATED` or `FAILED`, publish or drop that candidate deliberately. Do not create a second candidate for the same tag.
- If the candidate contents are wrong, drop it and prepare a new version and immutable tag. Do not move or replace a release tag that others may already have fetched.
