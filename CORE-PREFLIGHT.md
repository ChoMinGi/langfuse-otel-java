# 코어 집중 개발 선행 검증 결과

이 문서는 구현 이전의 phase-0 기록이다. 이후 구현의 API와 검증 범위는 [OBSERVATION-API.md](OBSERVATION-API.md)를 참고한다.

검증일: 2026-09-22~23 KST. 기준: main `33a8926b30d3aac4bb2a42e577146d396a5e69ff`,
core/starter `0.2.2-SNAPSHOT`, OpenTelemetry `1.62.0`.
작업 브랜치: `spike/core-preflight`. 기존 `chore/post-0.2.1` 브랜치는 보존했다.

**판정: 제한된 A/B 구현에 착수할 근거가 있다.** 기존 helper로 수집과 동기·비동기 연결은 가능하지만,
종료 상태의 일관성과 immutable context 경계를 소비자가 반복해서 구현해야 한다.
새 facade의 역할을 이 두 책임과 정책 적용으로 제한한다. 전체 자동 계측 전환이나 안정성 인증의 판정은 아니다.

이번 작업은 소비자 예제, 특성 검증 테스트, 임시 Docker 검증 환경과 결과 문서를 추가했다.
제품 소스·공개 API·기존 CI 설정·릴리스 버전은 변경하지 않았다. 커밋·push·PR·배포도 실행하지 않았다.

## 1. 실행 결과와 재현 자료

| 검증 | 실제 결과 |
|---|---|
| 전체 core/starter 테스트, Java 21.0.8 | core 125/125 통과, starter 203개 중 201 통과·2 skip, 실패 0 |
| 독립 소비자, Java 11.0.26 및 21.0.8 | 각 23개 통과 |
| 새 context/혼용 테스트, Java 17.0.14 | core 2개 + starter 3개 통과 |
| Langfuse 4.41.0 실제 수집·조회, Java 21 | 테스트 1개, trace 2개 × observation 3개 검증 통과 |
| 임시 자원 정리 | 이번 검증의 Docker 프로젝트·볼륨 제거 확인. 기존 `langfuse-*` 환경 유지 |

전체 테스트 수에는 이번에 추가한 core 2개와 starter 3개가 포함된다.
starter의 skip 2개는 기존 LangChain4j 1.18 취소 기능 조건부 테스트다.
`langChain4j118CancellationEndsOnceAndSuppressesLateTerminalSignals`와
`reentrantCancellationDoesNotDeadlockOnProviderTerminalAcknowledgement`를 현재 기본 의존성 조합에서는 실행하지 않았다.
따라서 이번 실행으로 해당 버전의 취소 동작까지 검증했다고 주장하지 않는다.

재현 명령과 예제는 [실험 디렉터리](experiments/core-preflight/README.md)에 있다.
기계 판독 요약은 [results.json](experiments/core-preflight/evidence/results.json),
합성 데이터의 실제 API 응답은 [본문 수집 활성화](experiments/core-preflight/evidence/live-v4-true.json)와
[metadata-only](experiments/core-preflight/evidence/live-v4-false.json)에 보관했다.

첫 live 검증에서는 응답의 선택적 `meta.cursor`가 생략된 경우를 검증 코드가 잘못 거부했다.
공식 명세의 “생략 시 마지막 페이지” 의미를 반영한 뒤 재실행했다.
한 번의 회귀 실행은 샌드박스의 소켓 생성 차단으로 5개 오류가 발생했고, 소켓 허용 환경에서 전부 통과했다.
이 두 실행 실패를 라이브러리 결함으로 집계하지 않았다.

## 2. 기존 helper로 충분한 부분과 부족한 부분

[RawOtelExamples.java](experiments/core-preflight/src/main/java/preflight/RawOtelExamples.java)는 공개 API만 사용하는
컴파일 가능한 동기·비동기 예제다. `getTracer()`, `recordInput/Output/Exception`, `LangfuseContext.applyTo()`를 사용한다.
외부 SDK 생성·sampler·processor·shutdown은 소비자 테스트에서 애플리케이션이 소유한다.

| 관찰 | 재현 결과 | 구현 판단 |
|---|---|---|
| 동기 Scope, 결과·예외 보존 | 부모 관계·Scope 복구·원래 예외 객체 보존 | 실행 wrapper나 범용 실행기는 불필요 |
| 비동기 완료 | 원래 stage를 반환하고 다른 스레드에서 span 종료 가능 | Scope를 비동기 수명 동안 보관하지 않음 |
| 종료 중복 | SDK가 중복 `end()`의 export를 막음 | 이것만으로 facade의 필요성을 주장하지 않음 |
| 오류 기록과 종료 경합 | 오류 속성을 기록한 경로보다 정상 `end()`가 먼저 종료해도 ERROR가 남음 | terminal 선택과 최종 속성 확정을 함께 처리해야 함 |
| 종료 후 본문 기록 | SDK는 속성 변경을 무시하지만 사용자 redactor는 여전히 실행됨 | 새 객체의 OPEN 검사와 최종 commit 검사 필요 |
| redaction과 종료 경합 | redactor가 진행 중이어도 end 가능; 늦게 끝난 값은 누락됨 | 사용자 redactor는 잠금 밖, 반환 후 상태 재검사 |
| 기본 수집 비활성 | 객체 문자열 변환·redactor를 호출하지 않음 | 현재 정책의 단락 평가 재사용 |
| fatal redactor 오류 | 합성 `VirtualMachineError`도 현재 helper가 삼킴 | 새 계약에는 기존 helper를 그대로 위임할 수 없음 |
| 외부 SDK | facade close/flush 이후 앱 span 기록 가능; alwaysOff sampler 존중 | 외부 SDK 변경·강제 exporter 추가 금지 |
| 타사 span의 context 속성 | 앱이 SDK 생성 시 공개 `applyTo(span, from(parent))` processor를 설치하면 복사됨 | 이미 생성된 SDK를 수정할 필요 없음 |

fatal 시험은 실제 메모리 고갈을 발생시키지 않고 테스트 전용 오류 객체를 사용했다.
현재 redactor의 fail-safe 계약은 유지하되, 새 경로의 fatal 분류·전파는 A/B 구현에서 명시적으로 분리해야 한다.
단순히 정책 객체를 재사용하고 종료용 CAS만 붙이는 구현으로는 제안 계약을 충족하지 못한다.

## 3. Context와 혼용 계약

현재 공개 `storeIn(parent, snapshot)`은 부모의 가변 `LangfuseTraceState`를 가리지 못한다.
이 상태에서 구 trace setter를 호출하면 나중에 읽은 snapshot도 달라진다.
또한 span에 다른 snapshot을 나중에 덧씌우는 방식은 생략한 이전 metadata를 제거하지 못한다.

[CorePreflightSnapshotBoundaryTest](langfuse-otel-core/src/test/java/io/github/chomingi/langfuse/otel/CorePreflightSnapshotBoundaryTest.java)에서
**기존 carrier를 새 snapshot으로 생성한 뒤 freeze하여 부모 Context에 저장하는 테스트 전용 경계**를 시험했다.
전역 resolver 수정 없이 다음을 확인했다.

- 실제 `LangfuseContextSpanProcessor`가 span 시작 시 새 snapshot을 선택한다.
- 원래 parent span 관계와 다른 애플리케이션 Context key는 유지된다.
- 원래 trace의 이후 변경과 작업 스레드의 legacy 값이 새 경계에 섞이지 않는다.
- 새 경계 아래 `LangfuseContext.setUserId()`는 동결된 snapshot을 바꾸지 못한다.
- 구 fluent `trace.userId()`는 동결된 carrier를 바꾸지 못해도 **자신의 span에는 직접 쓴다**.

이는 내부 재사용 가능성의 증거다. 공개 API에 frozen 경계를 추가한 상태는 아니다.

| 조합 | 첫 구현의 지원 범위 |
|---|---|
| 명시적 부모 A를 다른 스레드 B에서 사용 | `from(parent)`에서 선택한 snapshot만 사용. B의 ThreadLocal과 병합하지 않음 |
| 구 trace → 새 observation | 시작 시 복사·동결. 이후 구 setter 변경은 새 경계를 변경하지 않음 |
| 새 snapshot → 새 자식 | 부모·형제 변경 없이 상속. processor에도 시작 전에 같은 경계를 전달 |
| 새 observation → 구 wrapper | 부모 관계와 최초 속성 상속까지만 지원. 구 setter의 snapshot 일관성은 지원 제외 |
| 외부 SDK의 타사 span | 앱이 설치한 processor가 있을 때 별도 보장. 기본은 라이브러리가 생성한 span만 복사 |

새 경로의 우선순위는 **명시적 전체 snapshot → 선택한 부모 Context의 snapshot → 빈 snapshot/설정된 resource 기본값**이다.
환경·release의 기존 owned SDK resource 설정을 재사용하고, 없는 전역 user/session 설정을 새로 만들지 않는다.
필드별 null 병합과 이전 metadata 삭제는 넣지 않는다. 필요한 snapshot을 **span 시작 전** 확정하여 processor가 과거 값을 쓰지 않게 한다.

## 4. 자동·수동 계측과 비동기 의미

[CorePreflightMixingTest](langfuse-otel-spring-boot-starter/src/test/java/io/github/chomingi/langfuse/otel/spring/CorePreflightMixingTest.java)는
실제 Spring AI 모델 계측 경로에 합성 응답(input 5/output 7)을 연결했다.

| 구성 | 관측 수 | 기록된 total token 합계 |
|---|---:|---:|
| 수동 chain + 자동 generation | 2 | 12 |
| 같은 호출의 수동 generation + 자동 generation | 2 | 24 |
| `langfuse.enabled=false` + 명시적 core bean + 수동 generation | 1 | 12 |

마지막 경로는 모델 프록시와 annotation advice가 생성되지 않는 것도 확인했다.
해당 설정에서는 starter 설정 기반 core bean도 자동 생성되지 않으므로 애플리케이션이 core bean을 제공해야 한다.
새 전용 opt-in 설정은 만들지 않았다. 이 시험은 모든 프레임워크의 전역 Reactor hook 수명을 검증한 것은 아니다.

비동기 시험에서 확인한 범위:

- 미완료 `CompletableFuture`에는 완료 callback 1개가 남고 span은 export되지 않는다.
  애플리케이션이 future를 취소하면 callback이 해제되고 종료된다. 이는 참조 보유 경로의 재현이며 heap 누수 판정은 아니다.
- 관측 span만 끝내도 provider future는 완료·취소되지 않는다. 실제 취소는 애플리케이션/provider 책임이다.
- `CompletionException(CancellationException)`을 unwrap한 예제는 취소를 ERROR로 기록하지 않는다.
  모든 provider 고유 래핑을 분류한다고 보장하지 않는다.
- 취소된 future의 늦은 완료와 이미 끝난 span의 늦은 결과는 추가 export를 만들지 않는다.
- provider의 내부 시도 정보가 공개되지 않으면 예제의 의미는 논리적 호출 한 번이다. 시도별 토큰·실패를 추정하지 않는다.
- 부모가 먼저 종료되어도 같은 trace의 자식을 생성할 수 있다. 부모 종료는 자식 취소가 아니다.
- 첫 토큰 정보가 없으면 실제 Langfuse `timeToFirstToken`도 null로 남았다.

## 5. SDK 제한과 실제 v4 데이터

Langfuse `4.41.0`, 커밋 `d84020653a619a374bd36e4563d52a5db5590129`를 고정했다.
Docker manifest 및 arm64 digest는 [results.json](experiments/core-preflight/evidence/results.json)에 기록했다.
컨테이너의 OCI version/revision label도 runner가 검사했다.

임시 TLS endpoint의 `/api/public/otel/v1/traces`는 `x-langfuse-ingestion-version: 4`가 없으면 거부하도록 구성했다.
기존 owned exporter로 전송하고 Observations API v2로 다음을 확인했다.

- 각 trace에 정확히 CHAIN root + GENERATION + TOOL. 부모 ID와 시작·종료 순서 일치.
- 모든 observation의 user/session/name/tags/environment/release/version 및 공통 metadata 일치.
- `usageDetails` input=5, output=7, total=12와 모델명 보존. 미제공 usage는 빈 map.
- capture 활성 시 root·generation 본문 일치, metadata-only에서는 모든 input/output이 null.
- 공통 `metadata.run` 필터는 3개를 반환하고, 개별 `metadata.kind=tool` 필터를 추가하면 해당 tool 1개만 반환.

이는 장애 없는 환경의 수집 결과다. 네트워크 재전송 상황의 exactly-once 보장은 시험하거나 주장하지 않는다.

외부 SDK의 최종 `SpanData`를 대상으로 별도로 다음 반례를 확인했다.

| SDK 설정 | 실제 결과 | 지원 조건 |
|---|---|---|
| attribute value 길이 8 | usage JSON이 `{"input"`로 잘림 | JSON을 자르지 않는 라이브러리 구현만으로 외부 SDK 결과까지 보장할 수 없음 |
| attribute 수 1 | 먼저 기록된 다른 속성 때문에 observation type 누락 | 필수·공통·사용자 속성 예산을 함께 확보해야 함 |

초기 구현은 정수 usage를 기존 scalar 매핑으로 기록하고, 확장 usage/cost의 구조화된 표현을 임의로 잘라서는 안 된다.
외부 SDK의 길이 제한은 필요한 완성 값보다 충분히 크거나 무제한이어야 한다. attribute 수 역시 충분해야 한다.
코어가 타사 SDK의 최종 제한을 우회하거나 이미 생성된 SDK를 다시 구성한다고 약속하지 않는다.

공식 계약 기준: [v4 이행 가이드](https://langfuse.com/integrations/native/opentelemetry/migration-to-v4),
[4.41.0 API 명세](https://github.com/langfuse/langfuse/blob/v4.41.0/fern/apis/server/definition/observations.yml).
기존 v4 헤더·exporter 구현을 새로 만들 필요는 없다.

## 6. 최소 API와 다음 구현의 통과 조건

공개 타입은 `LangfuseObservation`과 `ObservationType` 두 개로 제한하는 방향을 권한다.
아래 항목은 이번에 구현한 API가 아니라 검증에서 도출한 A/B 범위다.

| 항목 | 필요한 이유 |
|---|---|
| `observation(name, type).parent(Context).traceContext(snapshot).start()` | span 시작 전에 부모·속성 경계를 확정. start는 Scope를 열지 않음 |
| `context()`, `makeCurrent()` | 짧은 현재 스레드 Scope와 비동기 수명을 분리 |
| `input(String)`, `output(String)`, `metadata(key,value)` | 정책을 적용한 본문과 명시적 observation metadata |
| `model(name)`, `usage(input,output)` | 기존 속성명을 재사용. 미제공 값과 0 구분, 잘못된 값을 자동 보정하지 않음 |
| `end()`, `fail(Throwable)`, `cancel()`, `close()` | terminal 선택·최종 상태를 한 번만 확정. close=end |

첫 토큰 편의 메서드, provider adapter, 실행기, registry, watchdog, 새 exporter·REST SDK는 이 범위에 추가하지 않는다.
확장 usage/cost/prompt 정보는 기존 경로를 유지하며 최소 facade에서의 안전한 표현은 별도로 결정한다.
초기 facade의 모든 메서드를 이 문서만으로 안정 API로 확정하지 않는다.

A/B에서 반드시 새로 검증할 조건:

1. 짧은 객체 잠금 안에서 OPEN 검사, terminal 선택, 준비된 속성 적용을 직렬화한다.
   SDK `span.end()` 및 사용자 redactor는 잠금 밖에서 실행한다. 한 호출만 SDK 종료를 수행한다.
   외부 SpanProcessor의 실행 비용을 잠금에 포함하지 않는다.
2. 본문·예외 redaction 결과는 잠금 밖에서 준비한 뒤 다시 OPEN을 검사한다.
   종료가 그 사이 확정되면 결과를 버린다. 먼저 끝난 준비 작업 자체가 terminal 승리를 뜻하지 않는다.
   종료가 이미 확정된 요청은 redactor를 호출하지 않는다.
3. fatal 오류를 삼키지 않도록 정책 준비 경로를 분리한다. 이 수정은 기존 경로의 동작 변경과 별도로 검토한다.
4. frozen context 경계를 SDK processor보다 먼저 적용한다. 신·구 혼용 범위를 위 표대로 테스트한다.
5. 종료 뒤 `context()`/짧은 `makeCurrent()`는 OTel과 같이 허용하되, observation의 값·상태는 불변으로 유지한다.
   다른 스레드에서 Scope를 닫는 사용은 지원하지 않는다.

새 종료 연산의 경합 구현은 아직 만들지 않았다. 이번 테스트는 **현재 helper의 반례**를 확인한 것이며
새 facade의 선형화·경합 안전성을 증명한 것이 아니다. A/B 완료 후 C의 장애·큐 포화·반복 부하로 진행한다.

## 7. 이번 검증의 한계

30분 부하, 2시간 soak, GC 이후 live heap 추세, heap dump, 모든 provider의 실제 취소,
재시도·전송 장애·큐 포화, app-root 필터링, 확장 cache/reasoning usage의 실제 수집은 이번 범위에서 실행하지 않았다.
0.2.1의 과거 검증 결과나 이번 정상 수집 6개만으로 운영 안정성·누수 없음·모든 프레임워크 호환성을 선언하지 않는다.
기존 API·starter 기본 동작과 프레임워크 CI는 A/B 동안 유지한다.
