# 0.2.1 stabilization

Working version: `0.2.1-SNAPSHOT`. Base: `02d82224c1a6720e58239e31c434502121ffe1dc`.
This patch retains Java 11 core, Java 17 starter, Spring Boot 3, and Spring AI 1 support.
Spring Boot 4 migration and new instrumentation features remain outside this patch.

## Implementation and acceptance criteria

- [x] Resolve `@ObserveGeneration` from the most-specific bridged target method, falling back to
  the invoked method. JDK proxies must emit exactly one correctly named observation for implementation
  annotations, final implementations, and generic reactive methods.
- [x] Preserve annotation precedence for both Spring AI and LangChain4j model beans, without duplicate spans.
- [x] Warn before skipping annotated non-proxyable model beans; preserve the original model behavior.
- [x] Include the post-0.2.0 owned-pipeline `alwaysOn` sampler fix and its upstream-parent regression tests.
- [x] Patch Netty to `4.1.138.Final` and Tomcat embed artifacts to `10.1.59`; keep Boot `3.5.16`.
  Apply equivalent overrides in Boot-parent example and consumer builds.
- [x] Enable grouped Maven patch update PRs, bump development consumers to `0.2.1-SNAPSHOT`, and
  use published `0.2.0` as the API compatibility baseline.
- [x] Update proxy limitations, dependency-management guidance, roadmap, and release instructions.
- [x] Make the disposable E2E truststore writable for read-only JDK distributions; reproduce the
  keytool permission failure and verify certificate import succeeds after correcting the copy's mode.

## Validation

- [x] Reproduce the annotation defect before the fix: four new JDK-proxy cases fail on the base code.
- [x] Java 21 baseline `clean verify`: tests, packaged JAR checks, coverage, Javadocs, and API compatibility.
- [x] Java 17 baseline `clean verify` and Java 11 core tests.
- [x] Latest supported adapter matrix: Spring AI `1.1.8`, LangChain4j `1.18.0` on Java 17 and 21.
- [x] Independent examples, framework consumers (baseline/latest), and optional prompt-client consumer.
- [x] Static analysis, dependency licenses, SBOM, and zero High/Critical vulnerability findings.
- [x] Disposable Docker Langfuse E2E with both frameworks and Observations API v2 read-back.

Local validation on 2026-09-21 (working tree, not a release-commit canary):

| Gate | Result |
|------|--------|
| Baseline `clean verify`, Java 17 and 21 | Each: 321 unit tests passed, 2 version-dependent tests skipped, 2 packaged-JAR tests passed; coverage, Javadocs, and API compatibility passed |
| Latest adapter unit tests, Java 17 and 21 | Each: 323 passed, no skips |
| Java 11 core | 123 passed |
| Independent consumers, Java 17 | Spring AI and LangChain4j baseline/latest: 2 tests each passed |
| Optional prompt consumer | 1 test passed |
| Examples | Both compiled and packaged |
| Quality profile | SpotBugs and dependency licenses passed; aggregate SBOM generated |
| Trivy 0.72.0, same version as CI | 0 High/Critical, 1 Medium finding |
| Docker Langfuse `4.0.0-rc.2` | Both frameworks passed actual OTLP ingestion and API v2 read-back; disposable stack cleaned up |

The scanner includes optional web dependencies. These results are a point-in-time check; the final
release must run the required CI again with the current vulnerability database.

## Publication gates

- [ ] Review and merge the patch; required CI must pass on the final commit.
- [ ] Set final `0.2.1` versions, publication date, and stable build timestamp.
- [ ] Export the exact release SHA canary and verify its hierarchy and attributes in Langfuse.
- [ ] Validate the signed candidate in Central, publish it, and verify resolution from an empty repository.
- [ ] Publish the GitHub release and update the README dependency snippets to `0.2.1`.

Follow [RELEASING.md](RELEASING.md). Development validation does not replace final-commit release gates.

## Dependency evidence

The 2026-09-21 main-branch [quality run](https://github.com/ChoMinGi/langfuse-otel-java/actions/runs/35575372259)
reported High/Critical failures in Netty `4.1.136.Final` and Tomcat `10.1.55`.
Tomcat's scanner fix hint `10.1.58` is not a public release; the
[official advisory](https://tomcat.apache.org/security-10.html) identifies `10.1.59` as the released fix.
Netty and Tomcat web dependencies are optional to consumers; application-owned dependency management
can override library versions and must be verified separately.
