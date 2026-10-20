# Community introduction and first-install feedback

As of 2026-10-21 (KST), [langfuse-examples PR #40](https://github.com/langfuse/langfuse-examples/pull/40)
is open and awaiting review. Its CLA check has passed. The feedback invitations below are drafts
and have not been posted.

## Submitted community listing

The PR adds one Java row to the **Community-maintained SDKs** table beside the existing JVM entry.
It identifies the library as MIT-licensed and production preview. The description introduces the
project and asks for inclusion; installation and verification details are in the project README.

PR title: `docs: add langfuse-otel-java to community SDKs`

Submitted table row:

```markdown
| Java     | OpenTelemetry-based Langfuse tracing with a standalone core and Spring Boot, Spring AI, and LangChain4j integrations (MIT, production preview): [ChoMinGi/langfuse-otel-java](https://github.com/ChoMinGi/langfuse-otel-java) | [@ChoMinGi](https://github.com/ChoMinGi) |
```

The [PR](https://github.com/langfuse/langfuse-examples/pull/40) has the current description and review
status. No separate discussion, issue, or promotional comment was posted.

## Feedback invitation — English

I maintain **langfuse-otel-java**, which sends Java traces to Langfuse through OpenTelemetry.
I'd like to hear where people get stuck setting it up.

If you have Java 11+ and a Langfuse project, the
[first-trace example](https://github.com/ChoMinGi/langfuse-otel-java/tree/main/examples/first-trace)
sends a fixed question and answer and prints a link once Langfuse receives the trace.
It uses the published library and needs no model API key.

Could you try it and [report how far you got](https://github.com/ChoMinGi/langfuse-otel-java/issues/new?template=first_trace_feedback.yml)?
Successful installs are useful to hear about too. Your Java/OS versions and any confusing step
are enough. Please remove credentials and private data. The library is production preview;
supported versions and limitations are in the README.

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

Reproduce reported failures and link fixes back to the issue. Ask for redacted details if needed.
A successful demo run is installation feedback, not evidence of production use.
Choose a destination and review the invitation before posting it.
