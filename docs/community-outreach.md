# Community introduction and first-install feedback

Prepared 2026-10-18. These are drafts, not submitted messages. No external-user feedback has
been collected yet. The public feedback entry point is the repository's
[first-trace issue form](https://github.com/ChoMinGi/langfuse-otel-java/issues/new?template=first_trace_feedback.yml).

## Proposed community listing

Target: the **Community-maintained SDKs** table in
[langfuse/langfuse-examples](https://github.com/langfuse/langfuse-examples#community-maintained-sdks),
checked on 2026-10-18. The table already includes a JVM SDK. Propose this as another integration
choice with an OpenTelemetry core; do not describe it as Langfuse's official Java SDK or the first JVM option.

Proposed PR title: `docs: add langfuse-otel-java to community SDKs`

Proposed table row:

```markdown
| Java | Community-maintained OpenTelemetry integration with a standalone Java core and Spring Boot, Spring AI, and LangChain4j integrations (MIT, production preview): [ChoMinGi/langfuse-otel-java](https://github.com/ChoMinGi/langfuse-otel-java) | [@ChoMinGi](https://github.com/ChoMinGi) |
```

Proposed PR body:

> Adds langfuse-otel-java as a community-maintained Java integration. I maintain this project.
> It provides explicit observations, parent context, content-capture policies, and owned/external
> OpenTelemetry setup, with optional Spring Boot, Spring AI, and LangChain4j integrations.
>
> The project is MIT-licensed and marked production preview. Version 0.2.3 is available on
> [Maven Central](https://central.sonatype.com/artifact/io.github.chomingi/langfuse-otel-core/0.2.3).
> Its [runnable first-trace example](https://github.com/ChoMinGi/langfuse-otel-java/tree/main/examples/first-trace)
> uses synthetic content without a model API key and checks ingestion through Observations API v2.
> [CI](https://github.com/ChoMinGi/langfuse-otel-java/actions/workflows/ci.yml) includes a pinned
> Langfuse 4.41.0 read-back check. The README documents supported versions and limitations.
>
> This is an additional OTel-based option alongside the existing JVM entry, not a request for
> official support. Please let me know if another section is more appropriate.

## Feedback invitation — English

I maintain **langfuse-otel-java**, a community Java integration for Langfuse through OpenTelemetry.
I'm looking for feedback on the first installation experience.

If you have Java 11+ and a Langfuse project, the
[first-trace example](https://github.com/ChoMinGi/langfuse-otel-java/tree/main/examples/first-trace)
downloads the published library, sends a small synthetic workflow, verifies ingestion, and prints
a link to the trace. No model API key is needed.

Could you try it and [report how far you got](https://github.com/ChoMinGi/langfuse-otel-java/issues/new?template=first_trace_feedback.yml)?
A successful install is useful feedback too. Java/OS versions, the confusing step, and what you
would connect next are enough. Please remove credentials and private data. The library is currently
production preview; supported versions and limitations are in the README.

## Feedback invitation — 한국어

Java에서 OpenTelemetry로 Langfuse를 연결하는 커뮤니티 라이브러리 **langfuse-otel-java**를
만들고 있습니다. 처음 설치할 때 막히는 부분을 찾고 싶습니다.

Java 11 이상과 Langfuse 프로젝트가 있다면
[첫 trace 예제](https://github.com/ChoMinGi/langfuse-otel-java/tree/main/examples/first-trace)를
실행해 보실 수 있습니다. Maven Central에 공개된 라이브러리로 고정된 예제 데이터를 보내고,
실제 수집을 확인한 뒤 trace 링크를 출력합니다. 모델 API 키는 필요하지 않습니다.

성공 여부, Java/OS 버전, 헷갈린 단계, 다음에 연결하고 싶은 환경을
[설치 피드백 양식](https://github.com/ChoMinGi/langfuse-otel-java/issues/new?template=first_trace_feedback.yml)으로
남겨주시면 도움이 됩니다. API 키나 실제 사용자 데이터는 제외해 주세요.
현재 production-preview 상태이며 지원 버전과 제한은 README에 공개하고 있습니다.

## Handling responses

For each voluntary report, reproduce the reported failure with the disclosed environment and
link a fix or documentation change back to that issue. Ask for redacted details only when needed.
Count successful installations separately from intended or ongoing use: trying the demo does not
establish production adoption. Keep outreach drafts here until a destination and submission are authorized.
