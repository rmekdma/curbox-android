# 앱 규칙 enforcement 리팩토링 계획

## 문서 목적과 기준점

이 문서는 `c17677ae` (`fix: enforce app rules across time boundaries`) 이후 앱 규칙 enforcement를
안정화하기 위한 실행 순서다. 이 문서는 제품 요구사항을 다시 정의하지 않는다. 각 phase는 먼저
결정된 정책을 테스트로 고정하고, 그 뒤 가장 작은 변경을 구현한다.

제품 의미, 수용 기준, 구조 결정과 미해결 상태의 기준 문서는 다음과 같다.

- 용어와 사용자 관점: [`CONTEXT.md`](../../CONTEXT.md)
- 제품 요구사항: [`docs/requirements/app-rules.md`](../requirements/app-rules.md)
- 인수 조건과 원래 이슈: [`docs/specs/app-rules-github-issue.md`](../specs/app-rules-github-issue.md)
- 구조 결정: [`ADR 0001`](../adr/0001-separate-app-groups-and-rules.md), [`ADR 0002`](../adr/0002-store-current-use-day-foreground-sessions.md), [`ADR 0003`](../adr/0003-use-best-effort-device-protection.md)
- 미해결 문제와 관찰된 한계: [`app-rule-enforcement-known-limitations.md`](../issues/app-rule-enforcement-known-limitations.md)

미해결 문제의 원인, 기기 증거, 제품 선택은 마지막 링크에 기록한다. 이 계획에는 같은 내용을
복사하지 않고 각 phase가 참조할 문제만 가리킨다. 실제 기기 검증 절차와 증거 형식은
[`ticket-02-device-verification.md`](../../.scratch/app-rules/evidence/ticket-02-device-verification.md)를 따른다.

이 문서에서 `module`, `interface`, `implementation`, `depth`, `deep`, `shallow`, `seam`,
`adapter`, `leverage`, `locality`는 codebase-design vocabulary의 정의로 사용한다. 설계가 줄 수
있는 것은 policy가 아니라 module의 depth, caller의 leverage, maintainer의 locality다.

## 완료 상태와 release gate

구현 완료, 실제 기기 검증 완료, 보고된 incident 종료는 서로 다른 상태로 기록한다. 현재
`c17677ae`의 구현 및 자동화 검증 결과는 `refactor implementation complete`로 별도 판정할 수
있지만, 이것만으로 OEM 해결이나 release readiness를 주장하지 않는다. 대상 샤오신 패드 프로
12.7 Android 16의 증거가 기록되기 전까지 다음 두 상태는 열린 상태로 유지한다.

- `AR004 Android16 device verification complete`: `열림`. 실제 기기에서 정책표의 시나리오와
  계측 결과를 남긴 뒤에만 완료로 바꾼다.
- `reported incident closed`: `열림`. AR004 완료 증거와 incident 재현 조건의 해결 확인이
  모두 있어야 종료한다.

이 문서의 Phase 0부터 Phase 4까지의 구조 개선 완료도 위 세 상태와 별도로 기록한다. 구현
완료 판정, 샤오신 기기 검증, incident 종료를 하나의 체크박스로 합치지 않는다.

## 목표

1. Android 16 및 OEM에서 `windows`, active root, foreground event가 비어 있거나 일시적으로
   실패해도 시간 경계의 enforcement가 조용히 사라지지 않게 한다.
2. foreground evidence 정책을 하나의 deep module에 모아 stale, null, partial, split-screen,
   keyguard, service reconnect의 의미를 한 곳에서 검증한다.
3. Accessibility callback에서 persistence와 rule evaluation을 분리해 main looper의 지연과
   예외 전파를 줄인다.
4. 동시에 보이는 package가 독립적인 deadline을 가질 필요가 실제로 남는 경우에만
   per-package recheck coordinator를 도입한다.
5. 서비스의 generation, cancellation, receiver cleanup을 유지하면서 재연결과 destroy 뒤의
   side effect를 차단한다.
6. 현재 요구사항의 세 가지 flavor와 기존 legacy blocker의 동작을 보존한다.

## 목표가 아닌 것

- Room entity, Room schema version, migration, usage ledger의 의미를 변경하지 않는다. 이 계획의
  enforcement 리팩토링만으로 데이터 모델을 다시 설계하지 않는다.
- Device Owner, Lock Task, kiosk 보장을 추가하지 않는다. 보호 수준은 [`ADR 0003`](../adr/0003-use-best-effort-device-protection.md)의 best effort로 남긴다.
- `gradle.properties`, `gradle/libs.versions.toml`, plugin, Kotlin, KSP 버전을 변경하지 않는다.
- Phase 0 전에 구체적인 public `interface`나 클래스 이름을 선결정하지 않는다. red test가 요구하는
  seam과 작은 interface를 확인한 뒤 선택한다.
- 앱 규칙의 사용일, 적용 범위, 사용 조건, 사용 가능 시간, 적립 허용량, 보호자 추가시간,
  제한 건너뛰기 의미를 바꾸지 않는다. 정책 변경이 필요하면 먼저 known-limitations 문서와
  요구사항/ADR를 갱신하고 별도 승인을 받는다.
- 줄 수를 줄이기 위한 shallow wrapper, 단일 구현만 있는 가설적 seam, 테스트가 private
  method 호출 순서를 검증하는 구조를 만들지 않는다.

## 기준 커밋에서 보존할 동작

`c17677ae`에서 의도된 외부 동작과 제품 의미는 phase가 끝나도 유지한다. 앱 규칙, 현재 사용일
session, guardian denial/approval, 세 flavor의 capability gate는 요구사항과 ADR을 기준으로
검증한다. 기준 커밋에서 이미 닫힌 implementation 경로인 all-apps fallback, callback
containment와 event recycle, split-screen keyed job, reconnect reconciliation, feature별 cleanup은
known-limitations 문서의 닫힌 항목을 regression contract로 삼는다. 그 항목의 상세와 증거를 이
계획에 다시 복사하지 않는다.

## 불변 조건

### 제품과 데이터

- 앱 규칙은 현재 사용일의 정확한 session 교집합으로 평가한다. active range 밖의 사용은
  전체 통계 의미를 훼손하지 않으며 해당 rule allowance를 소비하지 않는다.
- 보호자 추가시간은 rule별로 누적되고 실제 target 사용 중 소모된다. 제한 건너뛰기는 해당
  rule에만 적용되며 다른 rule과 전체 통계의 의미를 바꾸지 않는다.
- 동시에 보이는 application package는 독립 session으로 처리하고, 같은 package의 여러
  window는 한 package로 deduplicate한다.
- 설정은 `DataStoreManager`, Room은 `AppDatabase.getInstance()`를 통해 기존 multi-process
  소유권을 지킨다. runtime snapshot은 원자적으로 교체하고 오래된 generation은 새 결과를
  publish하지 않는다.

### Accessibility와 동시성

- 개별 feature나 window/root/persistence 실패가 `AppBlockerService`를 종료시키지 않는다.
  coroutine에서는 `CancellationException`을 다시 던지고, 그 외 nonfatal 오류는 기록하고
  삼킨다.
- `onAccessibilityEvent`에는 node tree traversal를 넣지 않는다. 비용이 큰 작업은 기존
  conflated worker로 보내고, 복사한 event는 성공, drop, exception, shutdown 모든 경로에서
  recycle한다.
- Handler callback은 lightweight wakeup/timer와 main-thread UI dispatch만 담당한다. decision
  worker로 옮긴 뒤에는 Room read/write와 evaluator 호출을 Handler에서 수행하지 않는다.
- null, empty, stale, partial 또는 예외가 난 window snapshot 하나만으로 이미 예약된
  enforcement deadline을 삭제하지 않는다. 반대로 신뢰할 수 있는 다른 nonessential active
  package와 keyguard/essential overlay는 오래된 foreground evidence를 되살리지 않는다.
- callback, receiver, worker, notification은 `setupReady`, `destroyed`, lifecycle generation과
  runtime generation을 확인한다. scheduler token은 implementation 안에서만 invalidate하며
  public generation으로 취급하지 않는다. destroy는 flag를 먼저 세우고 예약과 coroutine을
  취소한다.

### Flavor와 변경 범위

- shared source를 바꾸면 `full`, `playstore`, `fdroid`가 모두 컴파일된다. 선택 기능은 기존
  `BuildConfig` gate와 manifest 규칙을 따른다.
- Room/schema 및 Gradle 설정 파일은 변경 diff에 들어가지 않는다. 이 범위를 벗어나는 요구는
  이 계획의 phase를 중단하고 별도 결정을 만든다.

## Ordered phases

각 phase의 completion criteria를 모두 만족하기 전에는 다음 phase로 넘어가지 않는다. 실패한
red test를 임의로 완화해 green으로 만들지 말고, 정책/증거를 known-limitations 문서에 연결한다.

### Phase 0 — policy를 고정하고 deterministic red test를 만든다

**목적:** Android framework의 관찰값과 제품의 enforcement 결정을 분리해, 현재 실패를
   재현 가능한 외부 동작으로 표현한다.

1. `c17677ae`를 baseline으로 확인하고 기존 test/build 결과와 working tree를 기록한다.
2. 다음 입력 조합에 대한 policy matrix를 작성한다: 최근 실제 foreground event, active root의
   package/null/exception, application window 집합의 정상/stale/empty/partial/exception,
   essential overlay와 keyguard, screen off/on, split-screen, service reconnect, wall-clock
   경계와 elapsed-time 경계. 각 행에는 최소한 `VISIBLE`/`NOT_VISIBLE`/`UNKNOWN` 분류,
   최대 허용 지연, fail policy, 자동 테스트 mapping과 승인 근거 열을 둔다.

#### Foreground evidence policy matrix — APPROVED 2026-08-31

아래 표는 ticket 01의 승인된 실행 계약이다. 제품 owner/parent가 2026-08-31에 `A`로 전체
13개 행과 R5 A안을 승인했다. `VISIBLE`은 해당 패키지를 현재 전면
사용 후보로 평가할 수 있다는 뜻이고, `NOT_VISIBLE`은 신뢰 가능한 관찰이 현재 전면이
아님을 증명한다는 뜻이며, `UNKNOWN`은 어느 쪽도 증명하지 못했다는 뜻이다. 직접 관찰이
아닌 synthetic 재검사나 오래된 event fallback은 evidence TTL을 갱신하지 않는다.

재시도는 250ms, 500ms, 750ms의 최대 세 번으로 하며 첫 관찰부터 최종 조치까지의 기본
budget은 1.5초다. R5에는 A를 적용한다. B는 오차단을 줄이는 대안으로 tradeoff 기록에만
남기며 active contract가 아니다.

| ID | 상태 조합 | 승인된 evidence 분류 | 최대 허용 지연 | fail policy | 자동 테스트 mapping | 평가할 package | wait / retry | block / allow 결정 | evidence TTL 갱신 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | 최근 실제 event + 대상 active root package | `VISIBLE` | 즉시, ≤1초 | 직접 증거가 있으면 evaluator 결과를 적용 | `AR002-R01` | 대상과 snapshot에서 식별된 모든 비필수 application package | 없음 | 각 package를 평가해 denial이면 block, allow면 allow | 예, real event와 직접 root/window 증거 |
| R2 | 최근 event + active root null + 대상 application window 존재 | `VISIBLE` | ≤1초 | null root가 대상 window 증거를 무효화하지 않음 | `AR002-R02` | 대상과 식별된 모든 비필수 application package | 없음. 일시적 provider exception이면 bounded retry로 전환 | evaluator denial이면 block, allow면 allow | 예, 대상이 직접 window에서 확인된 경우 |
| R3 | 최근 event + 대상 window 없음 + 다른 비필수 root 없음. root null, window stale 또는 일시적 read exception 포함 | `VISIBLE` 단, event age가 5초 이내인 동안만 | ≤1.5초. 5초 TTL 만료 뒤 R5로 전환 | 최근 event를 bounded fallback으로만 사용하고 TTL을 연장하지 않음 | `AR002-R03` | 마지막 실제 event package 하나 | 최대 3회 bounded retry 후 마지막 event fallback | fallback 기간에는 evaluator denial이면 block, allow면 allow | 아니오, synthetic fallback은 갱신하지 않음 |
| R4 | 만료 event + 대상 application window 존재 | `VISIBLE` | 즉시, ≤1초 | 직접 대상 window가 만료 event보다 우선 | `AR002-R04` | 대상과 식별된 모든 비필수 application package | 없음 | evaluator denial이면 block, allow면 allow | 예, 직접 대상 window 증거 |
| R5 | 만료 event + 빈 window 목록 또는 null root, window/root provider exception 포함 | `UNKNOWN` | ≤1.5초에 제한 판정 | 마지막 비필수 package의 restriction을 fail closed | `AR001-R01`, `AR002-R05` | 마지막으로 알려진 비필수 package 하나 | 최대 3회 bounded retry 후 제한 판정 | evaluator가 명시적으로 allow할 때만 allow. denial 또는 evaluator 불능이면 block | 아니오 |
| R6 | 이전 후보가 없거나 이전 후보와 다른 비필수 active root가 확실히 존재. application windows는 empty, stale 또는 exception일 수 있음 | 이전 package는 `NOT_VISIBLE`, 새 root와 직접 식별 package는 `VISIBLE` | ≤1초 | 이전 후보가 있으면 contradictory nonessential root가 stale 이전 event보다 우선하고, 없으면 현재 root를 첫 후보로 채택 | `AR002-R06` | 이전 package는 평가하지 않음. 새 root와 직접 식별된 package만 평가 | 없음 | 이전 session은 종료하고 새 package는 evaluator denial이면 block, allow면 allow | 이전 package는 아니오. 새 package는 예 |
| R7 | launcher, System UI, IME 또는 guardian root + 대상 application window 존재 | 대상은 `VISIBLE` | ≤1초 | essential root는 application window의 직접 package 증거를 지우지 않음. guardian이면 중복 guardian 금지 | `AR002-R07` | 대상 window와 식별된 비필수 application package | 없음 | evaluator denial이면 기존 guardian 또는 현재 denial에 반영하고, allow면 allow. 새 guardian 중복 실행 없음 | 대상 직접 window가 있으면 예 |
| R8 | essential root + 대상 application window 없음 | `UNKNOWN` | reliable root/window 또는 USER_PRESENT까지 보류. 복귀 후 ≤300ms | stale 이전 package를 재평가하지 않고 기존 enforcement 상태를 유지 | `AR002-R08` | 없음 | nonessential root 또는 USER_PRESENT를 기다림 | 새 block/allow 없음. 이미 열린 guardian은 유지 | 아니오 |
| R9 | 부분 식별 split screen. 일부 application window package는 식별되고 일부 root는 null/exception | 식별 package는 `VISIBLE`, 누락 slot은 `UNKNOWN` | 식별 package ≤1초, 누락 slot ≤1.5초 | 식별된 package를 버리지 않으며 누락 package를 이전 event만으로 추론하지 않음 | `AR002-R09` | 식별된 각 비필수 package만 평가 | 누락 slot만 최대 3회 retry | 식별 package는 evaluator denial이면 block, allow면 allow. 누락 slot은 새 결정 없음 | 식별 package만 예 |
| R10 | service reconnect 또는 event 없는 cold start + 알려진 application window | `VISIBLE` | ≤1초 | 알려진 application window는 event/root 부재만으로 폐기하지 않음 | `AR002-R10` | 모든 식별된 비필수 application package | 없음 | 각 package는 evaluator denial이면 block, allow면 allow | 예, 직접 window 증거 |
| R11 | service reconnect 또는 cold start + empty windows + active root null/read exception | `UNKNOWN` | package evidence가 생긴 뒤 ≤1초. evidence 자체가 없을 때 package decision deadline 없음 | 임의 package를 발명하지 않고 다음 실제 event, window 또는 USER_PRESENT까지 보류 | `AR002-R11` | 없음 | 다음 실제 evidence를 기다림 | 새 block/allow 없음 | 아니오 |
| R12 | screen off | `NOT_VISIBLE` | 즉시 | 화면이 꺼진 동안 실제 foreground 사용으로 기록하지 않음 | `AR002-R12` | 없음 | 대기 없음. screen on에서 새 관찰 시작 | application session 종료. evaluator와 guardian 실행 없음 | 아니오 |
| R13 | keyguard 표시 중 | `UNKNOWN` | USER_PRESENT 뒤 ≤300ms에 재확인 | keyguard 아래에서 guardian을 띄우거나 stale package를 재평가하지 않음 | `AR002-R13` | 없음 | USER_PRESENT를 기다림 | 새 block/allow 없음. 기존 denial 상태는 유지 | 아니오 |

행별 근거와 승인 기록은 다음과 같이 별도로 관리한다. `owner`와 `approval evidence`는
각 행에 기록하고, 아래 표는 2026-08-31 parent 승인 메시지를 공통 근거로 남긴다.

| ID | 행별 rationale | owner | approval evidence |
| --- | --- | --- | --- |
| R1 | event와 active root가 같은 package를 가리키므로 현재 전면 평가의 가장 강한 정상 경로다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R2 | root가 null이어도 대상 application window가 직접 확인되면 실제 표시 증거가 있다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R3 | 최근 event는 전환 누락을 보완하지만 오래 유지하면 stale package를 살릴 수 있으므로 5초로 제한한다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R4 | event freshness와 무관한 직접 대상 window는 stale event보다 강한 현재성 증거다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R5 | AR 001의 핵심 tradeoff다. A가 승인되어 제한 우회를 줄이는 방향으로 확정됐다. B는 오차단을 줄이는 비활성 대안 기록이다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R6 | 이전 후보가 없으면 현재 비필수 root가 첫 후보가 되고, 이전 후보가 있으면 다른 비필수 root가 그 package가 계속 보인다는 가정과 직접 모순되므로 stale 이전 package를 잠그면 안 된다. window 목록이 empty, stale 또는 exception이어도 확실한 active root를 버리지 않는다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R7 | essential UI는 추적 대상이 아니지만 함께 보이는 application window는 유지해야 하며 guardian 재진입은 막아야 한다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R8 | essential UI가 앱을 완전히 가렸는지 split 상태로 남겼는지 알 수 없으므로 reliable evidence 전까지 보류한다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R9 | split screen의 독립 사용량을 보존하면서 null root package를 임의로 재구성하지 않기 위한 절충이다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R10 | reconnect 시 새 event가 없어도 실제 application window가 있으면 현재 표시 후보를 복원할 수 있다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R11 | package를 특정할 수 없는 cold start에서 block할 대상도 allow할 대상도 임의로 만들지 않는다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R12 | 요구사항의 “화면이 켜져 있고 실제로 보이는 동안만 기록”을 직접 반영한다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |
| R13 | keyguard 아래에 guardian을 표시하지 않고 unlock 뒤 실제 창을 다시 확인해야 중복과 stale lock을 피할 수 있다. | 제품 owner / parent — 승인됨 2026-08-31 | `APPROVED — parent replied "A" for the full matrix on 2026-08-31` |

R5의 승인된 A 선택과 보존된 B tradeoff는 다음과 같다.

- **A (승인됨, active contract):** bounded retry가 끝나면 마지막으로 알려진 비필수 package만 제한 판정에
  사용한다. evaluator가 명시적으로 allow를 반환할 때만 allow하고, denial 또는 evaluator
  불능은 block한다. 이 fallback은 사용량 session을 만들거나 evidence TTL을 갱신하지 않는다.
  오차단 가능성은 있지만 AR 001의 무기한 제한 누락을 줄인다.
- **B (비활성 대안 기록):** bounded retry가 끝나도 `UNKNOWN`을 유지한다. evaluator, 새
  guardian, 새 block/allow 결정을 만들지 않고 reliable application evidence까지 기존
  상태를 유지한다. 오차단은 줄지만 evidence가 계속 없으면 AR 001 제한이 지연될 수 있고,
  package decision에는 유한한 최대 지연을 약속할 수 없다. B는 승인된 실행 계약이 아니다.

parent는 2026-08-31에 `A`로 전체 13개 행과 R5 A안을 승인했다. 따라서 이 section의
`approval evidence`는 승인된 기록이며 ticket 01의 정책 승인 blocker는 해제됐다. Phase 0
전체 완료 여부는 별도의 deterministic red test와 나머지 완료 조건을 충족할 때 판정한다.
3. AR 001에 영향을 주는 모든 행은 Phase 0 blocker다. 각 행에 승인된
   `VISIBLE`, `NOT_VISIBLE`, `UNKNOWN` 의미, 최대 허용 지연, fail policy와 테스트 mapping이
   모두 채워져야 다음 phase나 release readiness 판정으로 진행한다. owner와 다음 결정만
   지정한 행은 통과로 보지 않는다. 이 결정은 별도 제품 의미 변경이 아니라면 requirements와
   ADR의 용어를 사용하고, 미결정 행은 known-limitations 문서에서 owner와 다음 결정을
   기록한다.
4. 실제 clock이나 `SystemClock.sleep`에 의존하지 않는 test harness를 만든다. 현재 private
   구조를 테스트하기 위한 임시 seam은 허용하지만, 이 phase에서는 concrete public interface를
   확정하지 않는다.
5. 아직 열려 있는 경로에 대해서는 외부 결과를 assert하는 red test를 추가한다: 긴 보호자
   추가시간 뒤 경계 재평가, empty window와 null root, stale nonempty list, 다른 active package로의
   전환, doze 이후 wall-clock boundary recovery, main callback의 비동기화와 destroy drain,
   재현된 refresh ordering 위험. 이미 닫힌 provider exception containment, copied event recycle,
   split-screen keyed deadline, reconnect reconciliation은 red test가 아니라 회귀 테스트로
   확인한다.
6. 각 red test가 현재 구현에서 실패하는지 확인하고, 실패 assertion이 policy 행 또는 known
   limitation 항목을 정확히 가리키는 증거를 남긴다. 닫힌 경로의 회귀 테스트는 기준 커밋에서
   green이어야 하며, baseline 자체의 unrelated failure는 별도로 표시한다.
7. 제품 동작 테스트와 concurrency/performance contract 테스트를 별도 범주로 기록한다. 제품
   동작 테스트는 activity launch, allow/block, persisted outcome처럼 사용자가 관찰하는
   결과를 검증하고, contract 테스트는 thread/queue idle, post-destroy side effect, latency
   budget과 같은 실행 계약을 관찰한다. 두 범주 모두 private call order를 테스트하지 않는다.

**완료 조건:**

- [ ] AR 001 관련 policy matrix의 모든 행에 승인된 `VISIBLE`, `NOT_VISIBLE`, `UNKNOWN` 의미,
      최대 허용 지연, fail policy와 자동 테스트 mapping이 있다. owner 지정만 있는 행은
      blocker로 남는다.
- [ ] AR 001, AR 003, AR 005, AR 007, AR 008에는 적어도 하나의 deterministic red test가
      있고, AR 010에는 stress 결과와 무관하게 deterministic interleaving test가 있다. 둘 다
      sleep이나 실제 elapsed time에 의존하지 않는다. AR 002는 모든 정책 행과 test mapping으로,
      AR 004는 실제 기기 검증 절차와 계측으로 증명 범위를 명시한다.
- [ ] AR 010은 100회 stress 결과만으로 닫지 않는다. latch, controllable Flow 또는 virtual
      scheduler로 receiver가 최신 값을 읽은 뒤 지연된 이전 emission이 publish되는
      deterministic interleaving을 강제하고, final snapshot, generation과 visible check를
      검증한다. 100회 stress는 보조 증거일 뿐이며, deterministic test가 위험을 반증하거나
      ordering contract/fix를 입증한 뒤에만 닫는다.
- [ ] red test는 activity launch, allow/block 결정, scheduler state, persisted outcome 등
      사용자가 관찰할 수 있는 결과를 검증하며 private call order만 검증하지 않는다.
- [ ] baseline command 결과와 known-limitations 링크가 기록됐고, 이 phase는 implementation
      code를 green으로 만들지 않았다.
- [ ] `gradle.properties`, `gradle/libs.versions.toml`, Room/schema 파일이 변경되지 않았다.

### 테스트 범주와 관찰 허용 범위

- 제품 동작 테스트는 activity launch, allow/block 결정, guardian 결과, persisted outcome 등
  외부에서 관찰 가능한 사용자 결과를 검증한다.
- concurrency/performance contract 테스트는 worker thread, queue idle, post-destroy side
  effect의 부재와 정해진 latency budget을 관찰할 수 있다. 이는 제품 동작 테스트의 대체가
  아니며, private method 호출 순서나 private field를 직접 검증하는 테스트는 계약 테스트로
  분류하지 않는다.

## Phase 1/2 architecture contract — APPROVED 2026-09-01

**상태:** `APPROVED`

이 section은 ticket 02부터 06까지의 Phase 0 RED 증거를 구현 가능한 구조 계약으로 번역한
승인된 architecture contract다. 2026-08-31에 승인된 foreground evidence policy matrix와
R5 A 선택은 제품 정책의 근거이며, 아래의 approval record는 이 architecture contract의
별도 승인 근거다. 동일 reviewer rebuttal이 수렴한 뒤 independent fresh Sol Medium reviewer와
parent가 D1부터 D9까지 모두 수렴했으며, 사용자는 그 결과를 근거로 2026-09-01에 구현
계속을 명시적으로 승인했다.

이 승인 기록은 문서/architecture 범위에만 적용된다. production implementation, Room,
Gradle, exported API와 ticket 07 status는 이 기록으로 변경하지 않는다.

이 설계에서 `module`, `interface`, `implementation`, `seam`, `adapter`, `depth`, `leverage`,
`locality`는 codebase-design vocabulary의 뜻으로 사용한다. `ForegroundEvidenceModule`과
`SerializedDecisionWorker`는 각각 작은 `interface` 뒤에 정책 복잡성과 순서 복잡성을 숨기는
deep `module`이다. 두 module의 이름은 approval draft를 위한 제안 이름이며, 승인 전에는
concrete class나 package를 선결정하지 않는다.

이 section의 §§1–5와 아래 §9 approval record가 Phase 1과 Phase 2의 유일한 normative
architecture source다. 뒤의 Phase 1/2 heading은 이 source의 interface, ownership, lifecycle
sequence, invariant를 실행하는 순서와 검증 checklist만 제공한다. Phase 1/2 heading의
문장과 이 source가 충돌하면 이 source와 승인 기록을 따른다. 뒤의 heading은 같은 규칙을
다른 표현으로 다시 정의하지 않는다.

### 1. 설계 경계와 최소 interface

#### 1.0 Shared value-level seam

두 module이 사용하는 관찰 입력은 Android-free `ForegroundObservationSource`에서 생성한다.
`ForegroundObservationSource`는 별도의 interface이며 정확히 하나의 operation인
`capture(...)`만 제공한다. capture의 입력과 반환값은 모두 immutable value다.
`ConnectionScopedSourceOrderSequencer`는 이 source와 분리된 connection-scoped allocator
interface이며, 정확히 두 operation인 `nextSourceOrderIdentity(): SourceOrderIdentity`와
`reserveRuntimePublication(): SourceOrderReservation`만 제공한다. 전자는 non-publication
observation에 사용하고, 후자는 settings/refresh publication의 `SourceOrderIdentity`와
`RuntimeRevision`을 하나의 atomic pair로 예약한다. `SourceOrderIdentity`와
`RuntimeRevision`은 typealias가 아닌 서로 다른 real value type이며, 두 domain의 raw numeric
value를 서로 비교하지 않는다. `LifecycleGeneration`도 이 둘과 구별되는 typealias가 아닌 real
value type이며, lifecycle owner가 setup/reconnect/destroy 경계에서 발급한다.

production `adapter`/callback은 event, synthetic wake 또는 runtime publication을 관찰한 순간
sequencer에서 source ordering value를 받고 framework event에서 immutable value facts를 만든다.
event와 synthetic wake 같은 non-publication observation은 `nextSourceOrderIdentity()`를 호출하고,
settings/refresh runtime publication은 `reserveRuntimePublication()`으로 source identity와
revision을 함께 받는다. caller와 worker는 두 identity를 직접 allocate하지 않는다. genuinely
fresh connection에서 connection-scoped sequencer를 초기화하거나 reset한 직후에만 host와 worker의
initial accepted snapshot이 `RuntimeRevision(0L)` sentinel에서 시작한다. 같은 lifecycle의
worker replacement는 fresh connection이 아니므로 host의 현재 `RuleRuntime`과
`latestRuntimeRevision`을 하나의 handoff로 상속하며 freshness를 0으로 낮추지 않는다. readiness
확인, host capture, 이전 worker stop, replacement construction과 installation은 private worker
handoff lock으로 하나의 critical section에서 직렬화하며, worker stop 중에는 `runtimeLock`을
잡지 않는다. worker는 standalone publication revision을 소비하지 않는다. value seam에 넘기기 전에 event와 생성한
framework copy를 무조건 recycle하며, worker는 platform event object의 소유권을 갖지 않는다.

| Value | Exact shape | Invariant |
| --- | --- | --- |
| `ConnectionScopedSourceOrderSequencer.nextSourceOrderIdentity` | `SourceOrderIdentity` real value type 반환 | non-publication event와 synthetic wake observation에 대해 connection 안에서 strictly increasing하며, runtime publication의 source identity는 atomic reservation에서 함께 발급한다. allocator는 이 sequencer 하나뿐이다. |
| `ConnectionScopedSourceOrderSequencer.reserveRuntimePublication` | `SourceOrderReservation` pair 반환 | settings/refresh runtime publication의 source identity와 revision을 같은 critical section에서 함께 strictly increasing하게 예약한다. pair를 별도 operation으로 나누지 않으며 event와 synthetic wake에는 새 revision을 발급하지 않는다. |
| `ObservationTrigger` | `sourceOrderIdentity: SourceOrderIdentity`, `kind`, nullable normalized `eventPackage`, `requestedAtWallMs`, `requestedAtElapsedMs` | identity는 source observation 시점에 sequencer가 할당하며 재사용하지 않는다. `kind`는 `REAL_EVENT`, `SYNTHETIC_RECHECK`, `REFRESH`, `RECONNECT`, `SCREEN_WAKE`, `USER_PRESENT` 중 하나이며, `REAL_EVENT` 이외에는 event package가 없어도 된다. |
| `ForegroundObservationSource.capture` | `ObservationTrigger`를 받아 raw `ForegroundFacts` 반환 | capture는 blocking persistence/evaluator를 수행하지 않으며, 반환된 value는 source 밖에서 변이되지 않는다. |
| `ForegroundFacts` | 아래 1.1의 raw immutable value | source는 한 capture 안에서 wall/elapsed 시각, root, window, display 사실을 서로 다른 시점에 재조회해 혼합하지 않는다. |

`ForegroundObservationSource`는 evidence policy를 해석하지 않는다. source는 framework 사실을
raw value로 옮기고, `ForegroundEvidenceModule`이 그 value와 하나의 canonical policy snapshot을
정책 결과로 분류한다. 따라서 production과 deterministic 두 `adapter`는 같은 source
`interface`를 만족하지만 policy branch를 각각 복제하지 않는다.

#### 1.1 ForegroundEvidenceModule

`ForegroundEvidenceModule`의 외부 `interface`는 다음 하나의 classify operation을 제공한다.
`classify(ForegroundFacts, ForegroundEvidencePolicySnapshot)`의 두 입력은 모두 cohesive한
framework-free immutable value다. `ForegroundFacts`에는 policy-derived visible package set, TTL
action, decision eligibility, retry attempt, concrete deadline을 넣지 않는다. worker가 request에
고정한 `AcceptedRuleRuntimeSnapshot.evidencePolicy`를 매 호출에 명시적으로 전달하며, module
implementation은 runtime/policy 전달 상태를 숨겨 보관하지 않는다.

| Operation | Input | Output | Side effect |
| --- | --- | --- | --- |
| `classify` | raw immutable `ForegroundFacts` value와 immutable `ForegroundEvidencePolicySnapshot` value | immutable `ForegroundEvidenceResult` value | 없음 |

`classify`의 호출은 하나의 owner가 같은 lifecycle generation 안에서 순서대로 수행한다.
module implementation은 policy delivery state를 보관하지 않는다. 다만 같은 lifecycle generation
안에서 R3 fallback에 필요한 마지막 `REAL_EVENT` 같은 raw evidence history는 보존할 수 있다.
accepted runtime snapshot이 교체되면 worker는 이전 runtime/policy로 만든 policy-derived evidence result,
session effect/decision permission, follow-up requirement, pending retry/deadline와 private
scheduler token을 invalidate한다. durable session history는 이 replacement 때문에 다시 쓰지
않으며, 보존된 raw history가 있다면 새 `AcceptedRuleRuntimeSnapshot`으로 다음 classify에서 다시
해석한다.
lifecycle generation이 바뀌면 raw history도 폐기한다. 따라서 policy replacement는 module의 숨은
전달 상태를 바꾸는 동작이 아니며, 보존되는 것은 같은 lifecycle의 raw history뿐이다.

`ForegroundFacts`의 value-level shape은 다음과 같다. 이 값은 framework가 보고한 사실과
capture 시각만 담는다. 모든 package set은 공백 package를 제거하고 중복을 제거한 불변 집합이어야
하며, 시간은 음수가 아니어야 한다. lifecycle/runtime generation과 essential package policy는
이 값에 넣지 않는다.

| Field | Value shape | Invariant |
| --- | --- | --- |
| `capturedAtWallMs` | `Long` | rule boundary와 use-day 계산의 기준 시각이다. |
| `capturedAtElapsedMs` | `Long` | module이 intrinsic `EvidenceValidity`를 계산할 때 참조하는 capture 시각이다. worker의 execution deadline은 별도의 worker-internal type으로 계산한다. |
| `signal` | raw `SignalFact` value: `kind`, nullable event package, nullable event wall/elapsed timestamp | `REAL_EVENT` 여부와 event package는 adapter가 관찰한 사실로만 전달하며 policy TTL을 여기서 계산하지 않는다. |
| `activeRoot` | raw `ActiveRootFact` value: nullable package name과 `AVAILABLE`, `EMPTY`, `FAILED` read state | `FAILED`와 `EMPTY`를 같은 확실한 nonessential root 증거로 해석하지 않는다. |
| `applicationWindows` | raw `ApplicationWindowsFact` value: reported package set, unknown slot count, `AVAILABLE`, `EMPTY`, `FAILED` read state, `FRESH`/`STALE` freshness | framework가 보고한 package만 보존하고, unknown slot과 partial read를 버리지 않는다. policy visible set을 합성하지 않는다. |
| `displayState` | raw `SCREEN_OFF`, `UNLOCKED`, `KEYGUARD` value | 화면 상태는 사실로만 전달하며 block/allow 결정을 여기서 만들지 않는다. |

`ForegroundEvidenceResult`는 policy branch를 caller에게 여러 독립 field로 재노출하지 않고,
classification, intrinsic evidence validity와 declarative follow-up kind을 value-only sealed
outcome으로 제공한다. sealed classification case는 classification domain meaning인
`SessionEvidenceEffect`와 `DecisionPermission`을 coherent payload로 명시적으로 함께 가질 수 있고,
이 payload를 유지한다. 여기서 “only”는 execution/scheduling state를 제외한다는 뜻이지
classification payload를 제외한다는 뜻이 아니다. follow-up은 kind만 선언한다. TTL과 eligibility는
서로 모순될 수 있는 별도 clump이 아니라 outcome case 안에서 함께 유도된다.

| Field | Value shape | Invariant |
| --- | --- | --- |
| `outcomes` | deterministic order의 `List<ForegroundEvidenceOutcome>` | 같은 package는 한 번만 나타나며, package가 특정되지 않은 `UNKNOWN`은 임의 package로 만들지 않는다. |
| `ForegroundEvidenceOutcome` | `Visible`, `NotVisible`, `Unknown` sealed/value classification case | case 자체가 `VISIBLE`, `NOT_VISIBLE`, `UNKNOWN` 분류이며, 각 case가 basis, session/decision 의미, intrinsic validity와 follow-up kind를 함께 가진다. |
| `Visible` | normalized package, evidence basis, `SessionEvidenceEffect`, `DecisionPermission`, `EvidenceValidity`, `FollowUpKind` | 직접 실제 증거만 evidence validity를 renew할 수 있고, 필요한 경우에만 evaluator 입력을 허용한다. |
| `NotVisible` | normalized package, evidence basis, `SessionEvidenceEffect`, `DecisionPermission`, `EvidenceValidity`, `FollowUpKind` | 이전 package 종료와 새 decision 금지 여부를 하나의 결과로 전달한다. |
| `Unknown` | nullable candidate package, evidence basis, `SessionEvidenceEffect`, `DecisionPermission`, `EvidenceValidity`, `FollowUpKind` | R5 A만 `EVALUATE_FAIL_CLOSED` candidate를 허용하며, candidate가 없으면 새 session/decision을 만들지 않는다. |

`SessionEvidenceEffect`, `DecisionPermission`, `EvidenceValidity`, `FollowUpKind`는 outcome case
안의 coherent value다. `EvidenceValidity`는 evidence 자체가 유효한지와 필요하면
`validUntilElapsedMs`를 나타내며, `FollowUpKind`는 evidence가 더 필요한 이유만 선언한다.

| Field | Value shape | Invariant |
| --- | --- | --- |
| `SessionEvidenceEffect` | `RENEW`, `PRESERVE`, `END_WITHOUT_RENEWAL` 중 하나 | evidence validity 갱신과 session 정리는 outcome case 안에서만 결정된다. |
| `DecisionPermission` | `EVALUATE`, `EVALUATE_FAIL_CLOSED`, `DEFER`, `DO_NOT_EVALUATE` 중 하나 | `EVALUATE_FAIL_CLOSED`는 R5 A의 마지막 package에만 사용하며, `DEFER`는 새 decision을 만들지 않는다. |
| `EvidenceValidity` | `RENEWED_UNTIL(validUntilElapsedMs)` 또는 `NOT_RENEWED` value | `validUntilElapsedMs`는 evidence 자체의 intrinsic validity 경계다. execution deadline, drain deadline과 같은 개념이나 type으로 사용하지 않는다. |
| `FollowUpKind` | `NONE`, `RETRY_FOR_RELIABLE_EVIDENCE`, `WAIT_FOR_RELIABLE_EVIDENCE`, `WAIT_FOR_USER_PRESENT` 중 하나 | worker가 다음 observation을 얻어야 하는 이유만 뜻한다. retry limit, backoff step, attempt, execution deadline, coalescing, generation, scheduler token 또는 callback operation은 포함하지 않는다. |

retry limit, backoff, attempt, execution deadline, wall-to-monotonic 변환, coalescing, Handler
post와 wake 재계산은 `SerializedDecisionWorker` implementation이 소유한다. module의 public
result에는 retry limit, backoff step, execution deadline, attempt, coalescing, generation,
scheduler token 또는 callback operation을 넣지 않는다. 따라서 evidence module은 evidence
classification에는 깊지만 scheduler에는 shallow pass-through가 되지 않는다.

#### 1.2 SerializedDecisionWorker

`SerializedDecisionWorker`의 외부 `interface`는 request handoff, lifecycle stop과 outcome
observation contract로 이루어진다. 세 surface 모두 Android, Room, Handler, `Job`,
`CoroutineScope`, `Continuation` 또는 다른 coroutine implementation type을 노출하지 않는다.

`DecisionOutcomeSink`는 worker interface의 필수 value-only collaborator다. worker implementation은
첫 request를 받기 전에 정확히 하나의 sink를 주입받고, 모든 terminal `DecisionOutcome`을
`publish(outcome: DecisionOutcome)`로 전달한다. production caller는 main-thread effect로
변환하는 production adapter를 주입하고, test caller는 같은 worker interface에 값을 기록하는
deterministic adapter를 주입한다. 이 sink와 두 adapter는 public external seam이며 private
test hook이 아니다. caller와 test는 모두 이 동일한 outcome seam에서만 worker 결과를 관찰한다.

| Operation | Input | Output | Contract |
| --- | --- | --- | --- |
| `submit` | immutable `DecisionRequest` value | `SubmissionResult` value | 호출자는 기다리지 않는다. request는 하나의 serialized queue owner에게 한 번만 전달된다. |
| `stop` | immutable sealed `StopRequest`: `RecoveryOnlyStop` 또는 `DeadlineDrainStop` | immutable sealed `DrainResult` value | numeric selection 전 production은 `RecoveryOnlyStop`만 사용한다. later explicit numeric selection 뒤에만 `DeadlineDrainStop`을 사용할 수 있다. |
| `DecisionOutcomeSink.publish` | immutable `DecisionOutcome` value | 없음 | accepted worker implementation은 publishable terminal outcome을 같은 sink에 한 번만 전달한다. cancellation으로 종료된 request에는 outcome을 만들지 않는다. sink는 Android effect나 Room row를 interface에 넣지 않는다. |

`DecisionRequest`의 value-level shape은 다음과 같다. 모든 request는 source observation 시점에
sequencer가 발급한 `SourceOrderIdentity`를 가진다. settings/refresh publication request만
`RuntimePublication`을 추가로 가지며, event와 synthetic wake request는 새 `RuntimeRevision`을
발급하거나 운반하지 않는다.

| Field | Value shape | Invariant |
| --- | --- | --- |
| `sourceOrderIdentity` | `SourceOrderIdentity` real value | event, synthetic wake와 runtime publication 모두 source observation 시점에 `ConnectionScopedSourceOrderSequencer`가 발급한다. caller와 worker는 allocate하지 않으며 queue는 값을 그대로 보존한다. |
| `lifecycleGeneration` | `LifecycleGeneration` real value | setup/reconnect/destroy 경계를 식별한다. `SourceOrderIdentity`나 `RuntimeRevision`과 대입하거나 raw numeric value로 비교하지 않는다. |
| `reason` | `REAL_EVENT`, `RECHECK`, `REFRESH`, `RECONNECT`, `SCREEN_WAKE`, `USER_PRESENT` 중 하나 | reason은 관찰 경로만 설명하며 제품 정책을 새로 만들지 않는다. |
| `observation` | 위의 raw immutable `ForegroundFacts` value | request 수락 뒤 caller가 변이시키지 않는다. policy-derived visible package set이나 TTL/eligibility/retry/deadline field를 포함하지 않는다. |
| `runtimePublication` | settings/refresh에서만 존재하는 nullable immutable `RuntimePublication` value | `runtimeRevision: RuntimeRevision`과 `candidateRuntime: RuleRuntimeSnapshot`을 원자적으로 운반한다. event와 synthetic wake에는 없다. |

`RuntimePublication.runtimeRevision`은 runtime-publication domain에서만 비교한다. worker는 request의
connection과 `LifecycleGeneration`을 먼저 검증하고, revision이 current accepted
`RuntimeRevision`보다 strictly newer일 때만 candidate runtime을 accept한다. worker는 revision을
allocate하지 않으며, `SourceOrderIdentity`와 `RuntimeRevision`을 서로 숫자로 비교하지 않는다.

`RuleRuntimeSnapshot`의 정확한 value shape은 다음과 같다.

| Field | Value shape | Invariant |
| --- | --- | --- |
| `snapshot` | complete normalized app-rule snapshot value | invalid snapshot은 request에 들어오지 않으며, snapshot의 rule/group 관계는 capture 시점에 일관된다. |
| `resetTime` | `hour`와 `minute`를 가진 use-day reset value | snapshot과 같은 runtime generation에서 해석한다. |
| `useDayGenerationStartedAtMs` | nonnegative `Long` | reset generation이 바뀌면 runtime generation과 함께 교체한다. |
| `overrideState` | immutable guardian override value | decision이 다른 generation의 override를 섞지 않는다. |
| `launchablePackages` | immutable normalized package set | all-apps scope 계산에 사용하며 evidence classification을 대신하지 않는다. |
| `evidencePolicy` | immutable `ForegroundEvidencePolicySnapshot` value | essential package policy의 유일한 canonical owner이며 raw facts나 다른 runtime field에 복제하지 않는다. |

worker가 newer `RuntimePublication`을 accept하면 candidate와 revision을 하나의 immutable real value
type인 `AcceptedRuleRuntimeSnapshot`으로 결합한다. `AcceptedRuleRuntimeSnapshot`은 typealias가
아니며 `runtime: RuleRuntimeSnapshot`과 `runtimeRevision: RuntimeRevision`을 가진다. worker는 각
request를 시작할 때 current `AcceptedRuleRuntimeSnapshot` 하나를 고정하고 classify, persistence,
evaluator와 private follow-up policy 전체에서 같은 값을 사용한다. request 처리 중 settings,
override, reset 또는 clock 입력을 다시 읽어 섞지 않는다.

`ForegroundEvidencePolicySnapshot`은 `RuleRuntimeSnapshot` 안에 한 번만 존재하는 cohesive
evidence policy value다. 그 안의 `essentialPackages`가 essential package의 유일한 canonical
owner/snapshot이며, `ForegroundFacts`, module result, 별도 caller snapshot에는 같은 권한의
복사본을 두지 않는다. 이 policy value는 승인된 evidence 해석에 필요한 안정적인 설정만
보유하고, observation별 visible package, TTL action, decision eligibility, retry attempt,
concrete deadline을 보유하지 않는다. `SerializedDecisionWorker`만 current
`AcceptedRuleRuntimeSnapshot`을 소유하고, 그 snapshot의 evidence policy를 `classify`에 전달한다.
module은 accepted runtime snapshot의 교체나 전달 상태를 소유하지 않는다.

| `ForegroundEvidencePolicySnapshot` field | Value shape | Invariant |
| --- | --- | --- |
| `essentialPackages` | immutable normalized package set | 이 field만 essential package의 canonical owner/snapshot이다. raw facts, module result, request caller가 별도 copy나 대체 authority를 갖지 않는다. |

`SubmissionResult`는 `ACCEPTED`, `REJECTED_NOT_READY`, `REJECTED_STALE` 중 하나다. rejection은
service를 종료시키지 않으며, value-only request를 drop한다. framework event는 value seam에
들어오기 전에 production adapter/callback이 무조건 recycle했으므로 worker나 rejected request가
framework object cleanup을 수행하지 않는다.

worker의 value-level output은 private implementation state가 아니라 public `DecisionOutcome`으로
관찰한다. `DecisionOutcome`은 source identity, lifecycle generation, accepted runtime revision,
package별 `decision` value,
`commitStatus`, `followUp`, `publicationStatus`를 가진다. `publicationStatus`는
`PUBLISHED`, `DEFERRED`, `DROPPED_STALE`, `RECOVERABLE_FAILURE` 중 하나이며, public
`DecisionOutcomeSink`는 이 value를 전달받을 뿐 Android activity나 Room row를 interface에 넣지
않는다.

`DecisionOutcome`의 정확한 value shape은 다음과 같다.

| Field | Value shape | Invariant |
| --- | --- | --- |
| `sourceOrderIdentity` | source request의 `SourceOrderIdentity` | 어떤 outcome도 다른 request identity를 참조하거나 새 identity를 allocate하지 않는다. |
| `lifecycleGeneration` | `LifecycleGeneration` | input request의 lifecycle generation을 반영하며 다른 ordering domain과 비교하지 않는다. |
| `acceptedRuntimeRevision` | evaluator가 사용한 `AcceptedRuleRuntimeSnapshot.runtimeRevision` | worker가 실제 사용한 accepted revision을 반영한다. outcome은 새 revision을 allocate하지 않는다. |
| `packageDecisions` | deterministic order의 `List<PackageDecision>` | package별 최대 하나이며, `EVALUATE`된 package만 포함한다. |
| `PackageDecision.packageName` | normalized package value | blank가 아니다. |
| `PackageDecision.isAllowed` | `Boolean` | 기존 evaluator 결과를 그대로 반영하며, worker가 product meaning을 재해석하지 않는다. |
| `PackageDecision.denyingRuleIds` | immutable rule ID list | allow인 decision에는 비어 있고, denial이면 evaluator가 보고한 denial만 담는다. |
| `commitStatus` | `NOT_REQUIRED`, `COMMITTED`, `FAILED`, `RECOVERY_REQUIRED` 중 하나 | `NOT_REQUIRED`는 필요한 persistence write set이 비어 있는 유효한 성공 상태다. `NOT_REQUIRED` 또는 `COMMITTED`만 current generation의 publishable outcome이 될 수 있고, `FAILED`/`RECOVERY_REQUIRED`는 decision을 publish하지 않는다. |
| `followUp` | `FollowUpKind` value | declarative kind만 외부에 관찰된다. retry limit, backoff step, attempt, execution deadline, coalescing state, generation, scheduler token과 scheduler post는 worker implementation이 소유하며 outcome은 이를 재노출하지 않는다. |
| `publicationStatus` | `PUBLISHED`, `DEFERRED`, `DROPPED_STALE`, `RECOVERABLE_FAILURE` 중 하나 | current `LifecycleGeneration`과 accepted `RuntimeRevision`이고 commit이 `NOT_REQUIRED` 또는 `COMMITTED`이면 package decision이 비어 있어도 no-write outcome을 `PUBLISHED`할 수 있다. stale lifecycle/revision은 `DROPPED_STALE`이며 activity/notification/scheduler follow-up을 publish하지 않는다. |

`DecisionOutcome`의 publication rule은 다음 하나로 고정한다. current `LifecycleGeneration`과
accepted `RuntimeRevision`의
결과이고 `commitStatus`가 `NOT_REQUIRED` 또는 `COMMITTED`이면, package decision이 없어도
`DecisionOutcomeSink`에 `PUBLISHED`로 전달할 수 있다. `FAILED`, `RECOVERY_REQUIRED`, stale
generation 또는 cancellation 결과는 decision을 publish하지 않으며, evidence를 더 기다리는
경우에는 `DEFERRED`를 사용한다.

`DecisionOutcomeSink`의 `publish`는 worker interface를 통과하는 유일한 결과 관찰 경로다.
production adapter는 outcome을 main-thread activity/notification effect로 변환하고 deterministic
adapter는 immutable outcome과 generation을 기록한다. 두 adapter 모두 같은 value contract를
통과하며 Android effect type은 production adapter implementation 안에만 남는다.

worker 내부에는 private `FollowUpPolicy`와 `DerivedFollowUpPlan`이 있다. 이 policy는 request마다
한 번 고정한 immutable `AcceptedRuleRuntimeSnapshot`, module이 반환한 `FollowUpKind`, private
attempt와 현재 runtime state를 입력으로 받아 retry/backoff와 private `ExecutionDeadline`을 계산한다.
`DerivedFollowUpPlan`만 scheduler adapter에 전달하며, `FollowUpPolicy`, attempt와 plan은
`ForegroundEvidenceModule`이나 public outcome seam을 통과하지 않는다. module의
`EvidenceValidity`/`validUntilElapsedMs`, worker의 `ExecutionDeadline`, D6의
`TotalDrainDeadline`은 elapsed clock을 사용하더라도 서로 다른 개념과 type이다.

`StopRequest`는 `RecoveryOnlyStop`과 `DeadlineDrainStop`의 sealed value다. 대표 측정과 later
explicit numeric selection 전 production stop은 반드시 `RecoveryOnlyStop`이다.
`RecoveryOnlyStop`에는 `TotalDrainDeadline`이 없고 drain completion guarantee도 없다. stop
acceptance와 동시에 lifecycle/generation을 invalidate하고 outcome을 suppress하며 scheduler와
worker work를 cancel한다. 끝내지 못한 durable state는 reconnect recovery 대상으로 남긴다.

later explicit numeric selection 뒤에만 production이 `DeadlineDrainStop`을 사용할 수 있다.
이 case는 stage마다 다시 시작하지 않는 하나의 absolute `TotalDrainDeadline`을 가진다.
candidate deadline injection은 deterministic test와 representative measurement에서만 허용하며,
production numeric selection이나 default의 근거로 오인하지 않는다.

| Drain value | Exact shape | Invariant |
| --- | --- | --- |
| `RecoveryOnlyStop` | `requestedAtElapsedMs`, `reason`, `lifecycleGeneration: LifecycleGeneration` | `TotalDrainDeadline` field가 없다. 즉시 invalidate/suppress/cancel하고 unfinished durable state를 recovery 대상으로 남긴다. |
| `DeadlineDrainStop` | `requestedAtElapsedMs`, `deadline: TotalDrainDeadline`, `reason`, `lifecycleGeneration: LifecycleGeneration` | later explicit numeric selection 뒤 production에서만 허용한다. persistence, evaluator, effect와 scheduler cleanup이 하나의 absolute endpoint를 공유한다. |
| `StopRequest.reason` | `DESTROY`, `RECONNECT`, `REPLACEMENT` 중 하나 | reason은 lifecycle 원인만 설명하며 stop mode나 product meaning을 바꾸지 않는다. |
| `DrainResult.RecoveryOnly` | `remainingWork`, `durableRecoveryRequired` | completion 또는 timeout을 주장하지 않으며 `completed`, `timedOut`, `completedAtElapsedMs` field가 없다. |
| `DrainResult.DeadlineDrain` | `completed`, `timedOut`, `remainingWork`, `durableRecoveryRequired`, `completedAtElapsedMs` | `completed`/`timedOut`과 completion 시각은 `DeadlineDrainStop`에만 적용한다. requested deadline 이후를 completed로 보고하지 않는다. |

### 2. Ownership table

| Owner | Owns | Must not own |
| --- | --- | --- |
| 기존 accessibility host | lifecycle callback, 다른 blocker의 기존 fan-out, setup/reconnect/destroy 진입 순서 | Room read/write, evaluator 호출, evidence policy branch, decision queue의 별도 복제 |
| `ConnectionScopedSourceOrderSequencer` | connection 안의 non-publication `SourceOrderIdentity`와 settings/refresh publication의 atomic `SourceOrderReservation`을 발급하는 유일한 allocator | policy 해석, request 처리, 두 domain의 numeric 비교, lifecycle generation 발급 |
| `ForegroundObservationSource` seam의 production adapter/callback | sequencer에서 observation 시점의 identity를 받아 framework fact를 immutable value로 만들고, value seam에 들어오기 전에 모든 경로에서 무조건 copy/recycle한 뒤 한 번 submit한다. settings/refresh publication이면 revision도 함께 받는다. | identity/revision 직접 allocation, policy에 따른 block/allow, session persistence, framework event object handoff |
| `ForegroundEvidenceModule` implementation | 명시적으로 받은 raw facts와 canonical `ForegroundEvidencePolicySnapshot`의 의미 해석, essential filtering, stale/partial/split-screen/keyguard/screen-off classification, intrinsic `EvidenceValidity`, declarative `FollowUpKind`, sealed outcome 생성 | policy delivery state, retry/backoff/attempt, timer post, `ExecutionDeadline` 계산, wall-clock 재계산, coalescing, Room, session writer, evaluator, activity/notification |
| `SerializedDecisionWorker` implementation | connection/`LifecycleGeneration` 검증, newer `RuntimeRevision` publication만 수락, current `AcceptedRuleRuntimeSnapshot` 소유/교체, request마다 accepted snapshot 하나를 고정해 classify/persistence/evaluator에 사용, visible session reconciliation, flush/commit, evaluator, private `FollowUpPolicy`와 retry/coalescing state, `ExecutionDeadline`과 wall-clock boundary 재계산, scheduler에 derived plan 전달, latest lifecycle/revision 검증, public outcome sink 전달 | identity/revision allocation, 두 ordering domain의 numeric 비교, Android object 보관, Handler callback에서 직접 DB 작업, caller별 별도 queue, framework event recycle, 제품 의미를 policy matrix 밖에서 변경 |
| session persistence implementation | 기존 repository abstraction과 multi-process Room owner를 통해 worker가 지정한 serialized session boundary와 checkpoint를 durable하게 commit | worker 순서 밖에서 독립적으로 rule decision을 실행하거나 기존 repository abstraction을 제거 |
| scheduler adapter | worker가 공급한 private `DerivedFollowUpPlan`의 post/cancel mechanics와 이미 post된 plan이 실행될 때 supplied value-only wake callback을 전달하는 completion | plan/deadline derivation, wall-clock read, wall-to-monotonic 변환, retry/coalescing ownership, Room/evaluator, stale callback의 재예약 |
| `DecisionOutcomeSink` adapter | public `DecisionOutcome` value를 production effect 또는 deterministic recording으로 변환 | stale outcome publish, evaluator 재실행, policy branch 복제, Android/Room type를 worker interface에 노출 |
| `AppUsageTracker`의 Phase 2 caller 역할 | sequencer가 발급한 identity/revision을 raw `ForegroundFacts` 또는 runtime publication과 함께 한 번의 serialized handoff로 전달 | identity/revision allocation, handoff 이후 visible reconciliation, `runBlocking` persistence, decision queue와 별도 순서 만들기 |

핵심 ownership은 `SerializedDecisionWorker` 하나다. request가 worker에 수락된 뒤에는
다음 순서를 다른 owner가 가로채지 않는다.

`visible reconciliation → all required session flush/commit → rule decision → next-plan
calculation → latest-generation side effect`.

이 순서는 단순 호출 순서가 아니라 `SerializedDecisionWorker` interface의 ordering
invariant다. session commit이 성공적으로 관찰되기 전에는 evaluator와 `nextPlan`을 실행하지
않는다. decision이 stale이면 public sink에 stale decision을 보내지 않고, private scheduler
token의 stale callback과 재예약도 버린다.

### 3. Ordered lifecycle sequence

1. **Setup:** lifecycle owner가 새 `LifecycleGeneration` real value를 발급하고 connection마다
   `ConnectionScopedSourceOrderSequencer` 하나를 만든다. 이전 lifecycle의 request, private scheduler
   token과 effect publication을 invalidate한다. runtime snapshot은 완전한 value로 한 번 캡처하고,
   receiver는 setup-ready 이후에만 등록한다.
2. **Capture:** accessibility callback과 production adapter는 framework event를 관찰한 순간
   sequencer의 `nextSourceOrderIdentity()`를 호출하고 필요한 immutable raw `ForegroundFacts`를 만든
   뒤 value seam에 들어오기 전에 원본과 복사본을 무조건 recycle한다. synthetic wake도 발생을
   관찰한 source가 같은 방식으로 identity를 받는다. node tree traversal, Room, evaluator, wait는
   이 callback에서 실행하지 않는다.
3. **Handoff:** callback은 `sourceOrderIdentity`, `LifecycleGeneration`, reason과 raw facts를 넣어
   `submit`하고 즉시 반환한다. settings/refresh publication이면 source가 같은 관찰 시점에
   `reserveRuntimePublication()`으로 받은 atomic pair와 candidate runtime을 넣는다. worker queue에는
   framework event object를 넣지 않는다.
4. **Evidence:** worker가 같은 serialized path에서 request마다 하나의 accepted immutable
   `AcceptedRuleRuntimeSnapshot`을 확정하고, 그 snapshot의 `ForegroundEvidencePolicySnapshot`과 raw
   `ForegroundFacts`를 `ForegroundEvidenceModule.classify(ForegroundFacts,
   ForegroundEvidencePolicySnapshot)`에 명시적으로 전달한다. module은 두 value를 해석해 intrinsic
   validity와 declarative `FollowUpKind`를 포함한 sealed outcome을 한 번 만들고, caller는 policy
   branch나 policy delivery state를 복제하지 않는다.
5. **Visible reconciliation:** worker가 outcome의 session effect와 직접 식별된 package를 session
   reconciler에 전달한다. 이전 package finish와 current-use-day boundary split을 먼저 처리하고,
   새 package start는 필요한 finish가 완료된 뒤에만 수행한다. unknown fallback package는 session으로
   만들지 않는다.
6. **Commit:** worker가 session checkpoint와 current-use-day persistence를 commit하고 성공
   여부를 확인한다. write set이 비어 있으면 `NOT_REQUIRED`를 유효한 성공으로 사용한다. commit
   실패는 partial decision을 만들지 않고 durable recovery 대상과 nonfatal error를 기록한다.
7. **Decision:** commit이 `NOT_REQUIRED` 또는 `COMMITTED`로 확인된 뒤에만 evaluator를 실행한다.
   package별 allowance, guardian override, use-day와 기존 rule meaning은 그대로 사용하며,
   evaluator는 outcome의 허용된 package만 받는다.
8. **Plan and publish:** worker 내부의 private `FollowUpPolicy`가 `AcceptedRuleRuntimeSnapshot`,
   `FollowUpKind`, private attempt와 runtime state에서 retry/backoff와 `ExecutionDeadline`을
   도출한다. worker는 allowance, skip, schedule, use-day boundary와 필요한 wall-clock 재계산을
   수행하고, private scheduler adapter에는 도출된 `DerivedFollowUpPlan`의 post/cancel mechanics만
   맡긴다. post된 plan의 supplied value-only wake callback delivery는 그 plan 실행의 completion이며
   별도 policy/plan ownership이 아니다. 최신 `LifecycleGeneration`과 accepted `RuntimeRevision`을
   확인한 뒤 public `DecisionOutcomeSink`에 outcome을 전달하며, activity와 notification effect는
   production sink adapter가 담당한다.
9. **Refresh:** settings collector와 refresh receiver는 observation 시점의 `SourceOrderIdentity`와
   runtime-publication domain의 `RuntimeRevision`을 sequencer에서 받아 하나의 serialized publication
   path로 보낸다. worker는 connection/`LifecycleGeneration`을 검증하고 current accepted revision보다
   strictly newer인 publication만 `AcceptedRuleRuntimeSnapshot`으로 교체한다. stale revision은
   거부한다. genuinely fresh connection에서만 initial accepted snapshot을 `RuntimeRevision(0L)`로
   초기화하며, same-lifecycle worker replacement는 host의 current accepted runtime과
   `latestRuntimeRevision`을 atomically inherit한다. accepted replacement 때
   이전 policy-derived outcome, session effect/permission, follow-up, pending deadline와 private
   scheduler token은 invalidate한다. durable session history는 다시 쓰지 않으며, 같은 lifecycle의
   raw evidence history만 보존할 수 있다. 이미 accepted된 최신 publication 뒤의 stale emission은
   무시한다.
10. **Screen and wake:** screen off는 `SCREEN_OFF` evidence와 session end 의미만 전달하고 새
    rule decision을 만들지 않는다. unlocked screen-on과 USER_PRESENT는 300ms contract 안의
    새 observation을 예약한다. wall-clock boundary는 wake 시 현재 wall time으로 다시
    계산하며 sleep 중 존재하지 않은 사용량을 소급하지 않는다.
11. **Reconnect:** 이전 lifecycle generation을 폐기하고 durable open-session recovery를
    수행한 뒤 새 observation을 capture한다. known application window가 있으면 event가 없어도
    reconciliation을 시도하지만, package evidence가 없으면 임의 package를 발명하지 않는다.
12. **Destroy:** 먼저 accepting flag와 `LifecycleGeneration`을 무효화한다. later explicit numeric
    selection 전에는 `RecoveryOnlyStop`으로 outcome을 즉시 suppress하고 scheduler/worker work를
    cancel하며, unfinished durable state를 reconnect recovery로 남긴다. 이 mode에는
    `TotalDrainDeadline`이나 drain completion guarantee가 없다. numeric selection 뒤의
    `DeadlineDrainStop`만 하나의 absolute deadline으로 drain을 시도한다. 어느 mode에서도 stop
    acceptance 뒤 evaluator, warning, notification 또는 새 scheduler callback을 publish하지 않는다.
    receiver cleanup은 한 feature의 예외가 다른 cleanup을 건너뛰지 않도록 독립적으로 containment한다.

### 4. Adapter와 seam table

외부에 공개할 real `seam`은 (1) framework 관찰값과 raw value-level facts 사이, (2) worker
outcome과 `DecisionOutcomeSink` 사이의 두 곳이다. 각 seam에는 서로 다른 두 `adapter`가 있어
실제 variation을 입증한다. production과 deterministic adapter는 각각 같은 value-only
interface를 만족하며, 그 interface와 evidence/worker contract에는 Android type이 없다.

| Seam | Interface shape | Production adapter | Deterministic adapter | Visibility |
| --- | --- | --- | --- | --- |
| framework facts → `ForegroundFacts` | capture signal을 받고 raw immutable facts value를 반환 | accessibility event, application window, active root, display/keyguard 사실을 value로 변환하고 value seam 전에 event를 무조건 recycle | virtual wall/elapsed/scheduler clock과 fresh/stale/empty/partial/null/exception facts를 공급 | real external seam; 두 adapter 모두 같은 contract suite를 통과 |
| worker outcome → `DecisionOutcomeSink` | immutable `DecisionOutcome` value를 `publish` | main-thread activity/notification effect로 변환 | immutable outcome과 `SourceOrderIdentity`/`LifecycleGeneration`/accepted `RuntimeRevision`을 기록 | real external seam; caller와 test가 같은 sink contract를 통과 |
| session persistence | worker implementation이 사용하는 read/commit value port | 기존 repository abstraction과 multi-process Room owner를 보존하는 production implementation | in-memory, delayed, fault-injecting fake | private internal test seam; 기존 repository abstraction을 제거하지 않으며 `SerializedDecisionWorker` public interface 밖 |
| scheduler | worker의 private `DerivedFollowUpPlan`을 받아 post/cancel하고, 이미 post된 plan 실행 시 supplied value-only wake callback을 전달하는 private mechanics port | monotonic Handler callback adapter | virtual-clock callback adapter | private internal test seam; callback delivery는 posted plan의 completion이다. plan/deadline derivation과 retry/coalescing ownership은 worker implementation 안에만 있다. |
| clock | wall/elapsed read를 제공하는 private value source | device clocks | independent virtual clocks | private internal test seam |
| follow-up policy | `AcceptedRuleRuntimeSnapshot`, `FollowUpKind`, private attempt/runtime state에서 private derived plan을 만드는 value operation | worker implementation 내부의 `FollowUpPolicy` | deterministic policy implementation 또는 fake | private internal test seam; module과 public outcome seam 밖 |

두 external adapter pair는 framework 사실 공급과 worker outcome 관찰에 각각 사용한다.
persistence, scheduler, clock과 follow-up policy는 worker module의 private internal implementation
seam이며, 테스트가 이를 사용하는 것은 public contract를 넓히는 근거가 아니다. 기존
repository abstraction은 이 private persistence seam을 통해 계속 보존한다. 현재 Phase 0 테스트의
reflection field와 temporary lambda는 migration 중 private internal test seam으로만 허용하고,
최종 contract의 caller가 private field나 private call order를 알 필요가 없게 한다.

### 5. Invariant contract

#### Ordering and generation

- connection마다 하나인 `ConnectionScopedSourceOrderSequencer`만 ordering values를 allocate한다.
  non-publication observation은 `nextSourceOrderIdentity()`를 사용하고, settings/refresh publication은
  `reserveRuntimePublication()`으로 source identity와 revision을 atomic pair로 받는다. caller와
  worker는 standalone publication revision을 발급하지 않는다. genuinely fresh connection에서
  sequencer를 초기화하거나 reset한 경우에만 worker initial accepted snapshot이
  `RuntimeRevision(0L)` sentinel을 사용하며, same-lifecycle replacement는 host의 current accepted
  runtime과 `latestRuntimeRevision`을 atomic handoff로 상속한다.
- event, synthetic wake와 runtime publication은 모두 source observation 시점에
  `SourceOrderIdentity`를 받는다. settings/refresh runtime publication만 별도의
  `RuntimeRevision`도 받는다. queue arrival이나 worker execution 시점에 identity/revision을
  다시 만들지 않는다.
- `SourceOrderIdentity`, `RuntimeRevision`, `LifecycleGeneration`은 typealias가 아닌 서로 다른 real
  value type이다. 서로 대입하거나 raw numeric value를 비교하지 않는다. source-order domain은
  interleaved request ordering에만, runtime-revision domain은 publication freshness에만 사용한다.
- worker는 connection과 `LifecycleGeneration`을 검증한 뒤 current accepted
  `RuntimeRevision`보다 strictly newer인 publication만 accept해 `AcceptedRuleRuntimeSnapshot`을
  교체한다. delayed stale revision은 source-order identity가 더 늦더라도 accepted runtime을
  되돌리지 않는다. private scheduler token은 callback을 invalidate하는 implementation state일 뿐
  세 public value type 중 하나가 아니다.
- side effect는 request의 `LifecycleGeneration`과 worker가 실제 사용한 accepted
  `RuntimeRevision`이 모두 최신일 때만 게시한다.
- stale result는 evaluator 결과 자체가 계산되었더라도 activity, notification, scheduler
  재예약을 public sink에 publish하지 않는다. private scheduler token의 stale callback도
  재예약하지 않는다. current visible session의 durable commit은 decision publication과
  별개로 정확히 한 번만 보존할 수 있다.
- contract test는 event와 runtime publication의 interleaving, newer publication 뒤 도착한 delayed
  stale `RuntimeRevision`, 그리고 `SourceOrderIdentity`/`RuntimeRevision`/`LifecycleGeneration`의
  compile-time noninterchangeability를 각각 검증한다.

#### Evidence, persistence, and decision ordering

- `VISIBLE`, `NOT_VISIBLE`, `UNKNOWN`은 승인된 13개 행에서만 유도한다. essential root는
  직접 application window를 지우지 않으며, 확실히 다른 nonessential root가 있으면 stale
  이전 package를 되살리지 않는다.
- module이 반환하는 `EvidenceValidity`와 `validUntilElapsedMs`는 evidence 자체의 intrinsic
  validity만 표현한다. worker가 private `FollowUpPolicy`로 계산하는 `ExecutionDeadline`이나
  D6의 `TotalDrainDeadline`로 재사용하거나 대체하지 않는다.
- session package set은 raw direct observation과 module outcome에서만 만든다. R5 A fallback은
  rule decision의 candidate가 될 수 있지만 session evidence validity를 갱신하거나 새 usage
  session을 만들지 않는다.
- 모든 이전 session finish/checkpoint commit이 끝난 뒤 current-use-day evaluator를 실행한다.
  evaluator는 persistence를 다시 수행하지 않고, worker 밖에서 session writer가 독립적으로
  실행되지 않는다.
- one package의 retry, replace, cancellation, failure가 다른 visible package의 session과
  deadline을 삭제하지 않는다. Phase 3 coordinator는 이 invariant의 증거가 생길 때까지
  만들지 않는다.

#### Error and CancellationException

- production adapter의 window/root/event read failure는 `FAILED` observation 또는 해당 policy의
  `UNKNOWN`으로 변환되고 nonfatal error로 기록된다. 하나의 provider failure가 host를 종료하지
  않는다.
- worker request 하나의 persistence, evaluator, scheduler, effect failure는 worker 전체를
  죽이지 않는다. 일반 exception은 기록하고 request를 recoverable failure로 끝낸 뒤 다음
  request를 처리한다.
- persistence commit이 실패하면 uncommitted session time이나 aggregate를 발명하지 않는다.
  open row는 지정된 durable recovery 경로로만 이어진다.
- 승인된 R5 A에서 bounded retry 후 evaluator가 명시적으로 allow하지 못하면 block이 우선한다.
  이 문장은 R5의 승인된 fail policy를 구현하는 것이며, 다른 행의 product meaning을 새로
  정의하지 않는다.
- worker coroutine implementation의 각 request child에서 suspend adapter, evaluator 또는 request
  code가 던진 모든 `CancellationException`은 그 request child 밖으로 재전파하고 nonfatal로
  logging하지 않는다. lifecycle owner는 이를 worker scope/generation cancellation과
  request-child cancellation으로 구분한다.
- worker scope/generation cancellation이면 현재 worker generation을 terminate한다. 그
  generation의 취소된 request와 이후 queued request는 outcome이나 side effect를 publish하지
  않으며, recovery와 다음 event 처리는 새 lifecycle generation/reconnect에서만 시작한다.
- request-child cancellation인데 worker scope가 살아 있으면 해당 request만 `CANCELED`로
  끝낸다. 그 request는 side effect와 outcome을 publish하지 않고, 같은 serialized worker
  generation이 다음 queued request를 처리한다. 이것이 ticket 06의 same-generation
  continuation contract다.
- `CancellationException`이 아닌 ordinary error는 request 단위로 기록하고 recoverable failure로
  끝낸 뒤 같은 worker generation이 다음 request를 처리한다.
- non-coroutine accessibility/Handler/synchronous adapter callback은 별도의 callback 경계에서
  ordinary feature exception을 contain하고 기록한 뒤 callback 밖으로 전파하지 않는다. 이
  callback은 framework event에서 immutable value facts를 만든 뒤 value seam 전에 event를
  무조건 recycle하고, worker의 suspend cancellation을 삼키거나 nonfatal failure로 변환하지
  않으며 `submit`의 nonblocking contract만 사용한다.

#### Retry and deadline

- retry limit, backoff step, attempt, coalescing state, `ExecutionDeadline`과 timer ownership은
  `SerializedDecisionWorker` implementation의 private `FollowUpPolicy`에 있다. evidence module은
  intrinsic `EvidenceValidity`와 declarative `FollowUpKind`만 반환하며 concrete timing을 반환하지
  않는다.
- private follow-up policy가 적용하는 승인된 bounded retry는 첫 observation 뒤 250ms, 500ms,
  750ms의 최대 세 번이며 기본 total decision budget은 1.5초다. scheduler는 derived plan을
  넘어 polling하지 않는다.
- R3는 최근 실제 event를 최대 5초 동안만 bounded fallback으로 사용할 수 있고 TTL을 갱신하지
  않는다. R5는 1.5초 뒤 마지막 비필수 package에 대해 승인된 A fail closed를 사용한다.
- R6의 확실한 다른 nonessential active root는 이전 package의 fallback을 즉시 무효화한다.
  R8과 R11은 reliable evidence 또는 USER_PRESENT까지 새 package decision을 만들지 않는다.
  R12는 대기하지 않고 session을 종료하며 evaluator와 guardian을 실행하지 않는다.
- wall-clock boundary와 private execution timing은 worker가 current wall/elapsed clock으로
  계산하고, wake/reconnect 때 worker가 현재 wall clock에서 다시 계산한다. scheduler adapter는
  worker가 계산한 `DerivedFollowUpPlan`만 post/cancel하고 wake를 value로 보고한다. doze 동안
  callback이 실행되지 않았다는 이유로 없는 foreground time을 ledger에 소급하지 않는다.
- scheduler adapter의 post가 false를 반환하거나 exception을 보고하면 worker가 request 단위
  recoverable failure로 기록하고, lifecycle/runtime generation과 private scheduler token이
  최신일 때만 worker 소유의 bounded recovery를 시도한다. stale callback은 결과와 재예약을
  모두 무시한다.

#### Performance and thread ownership

- accessibility callback의 synchronous work는 event/value capture와 bounded handoff뿐이며,
  callback nonblocking은 hard invariant다. Room read/write, evaluator, session reconciliation,
  rule planning은 callback 밖의 worker에서 수행한다.
- Handler는 lightweight wakeup/timer와 main-thread effect dispatch만 담당한다. worker로 옮긴
  뒤 Handler에서 Room read/write와 evaluator를 수행하지 않는다.
- worker는 하나의 serialized path를 사용한다. visible tracker queue와 decision queue를
  나누어 flush/evaluator ordering을 맞추지 않는다.
- 제품 latency는 policy matrix의 row budget을 따른다: 일반 직접 증거는 1초 이내, R5 bounded
  decision은 1.5초 이내, USER_PRESENT recovery는 300ms 이내다. callback blocking time의
  representative p95와 decision latency p95는 구현 후 측정해 계획 문서에 기록하되, 아직
  새로운 숫자 threshold를 승인된 제품 요구사항처럼 만들지 않는다.
- node tree traversal는 callback에 들어오지 않고 기존 conflated background worker의 recycle
  ownership을 유지한다. 새 observation adapter가 event copy를 만들면 모든 success, reject,
  drop, exception, shutdown 경로에서 value seam 이전 recycle을 contract test로 검증한다.

#### Total drain and reconnect

- destroy는 flag와 generation을 먼저 invalidate하고 새 request/effect를 받지 않는다.
- 대표 측정과 명시적 later numeric selection 전 production stop은 `RecoveryOnlyStop`이며
  `TotalDrainDeadline`이나 drain completion guarantee가 없다. stop acceptance와 동시에
  lifecycle/generation을 invalidate하고 outcome을 suppress하며 scheduler/worker work를 cancel한 뒤,
  unfinished durable state를 reconnect recovery 대상으로 남긴다. numeric selection 뒤의
  `DeadlineDrainStop`만 persistence, evaluator, effect와 scheduler cleanup이 공유하는 하나의 absolute
  `TotalDrainDeadline`을 가질 수 있고, 각 단계가 timeout을 다시 시작하지 않는다.
- total deadline 안에는 evaluator, warning, notification, handler callback의 post-destroy
  side effect가 0건이어야 한다. 이미 시작한 durable commit은 정상 완료하거나, timeout 뒤
  open row recovery로 남긴다.
- reconnect는 새 lifecycle generation과 새 worker state를 만들고, 이전 worker의 queue와
  effect를 재사용하지 않는다. durable session read와 window reconciliation으로 다음 event가
  없어도 복구할 수 있는 상태를 확인한다.
- reconnect recovery가 읽은 durable session은 현재 use-day와 generation filter를 지키고,
  destroy 중 취소된 in-memory tail을 실제 사용으로 소급하지 않는다.

Ticket15 evidence (2026-09-07): `AppRuleBlocker.destroyInternal()` invalidates the lifecycle,
generation and worker-instance token before cleanup, tracks in-flight refresh, notification and
callback work, and keeps production teardown on `RecoveryOnlyStop`. The candidate-only
`onDestroyForMeasurement()` path shares one `TotalDrainDeadline` across scheduler removal,
handler removal, worker drain, scope cancellation, receiver cleanup and effect drain; no stage
restarts the budget. `DeadlineDrainStop` snapshots queued, in-flight and durable work before
cancellation. Deterministic barrier tests hold a final decision, refresh mutex, notification
publication and handler callback together; after invalidation they observe zero evaluator,
warning, notification and handler publication effects, then release the barriers and verify that
an open durable session is recovered only through the documented `recoverOpenSessions()` path.
Independent barriers also cover the actual usage-reset completion broadcast and worker recheck-plan
registration/delivery, while notification, warning, visible-handler, rearmed-handler, scheduled
recheck, and alarm publication each have a final-call fence. Event and scheduled-provider tests
release an application-window read after destroy/reconnect and verify that the adapter-local
provenance cache is not repopulated.
The worker test also pauses an old worker after its final check, replaces it in the same lifecycle,
and verifies that the adapter-private typed worker-instance token allows only the replacement to
publish. A real
host test repeats setup → destroy → setup on the same blocker and verifies latest-generation
effects only. The candidate budget in these tests is measurement input only; no production
2-second or 5-second timeout was selected. The focused JVM regression and Full, Playstore and
F-Droid debug compile/build scope is recorded below.

Verification record for this implementation (JBR 21 at
`C:\Users\DELL\.jdks\jbr-21.0.11`): the focused
`SerializedDecisionWorkerTest` passed all 20 tests and `testFullDebugUnitTest` passed all 347
tests. `compileFullDebugKotlin compileFullDebugAndroidTestKotlin` passed, and
`assembleFullDebug assemblePlaystoreDebug assembleFdroidDebug` passed. On the attached iPlay50
mini Pro Android 13 device, the ticket11–15 focused instrumentation set had 61 tests with 43
passes: `AppRuleBlockerRecheckTest` 22/36, `AppRuleBlockerVirtualDozeWakeRedTest` 1/1,
`AppRuleBlockerDestroyFaultRedTest` 16/20, and `AppRuleBlockerRefreshOrderingRedTest` 4/4.
The two reproduced lifecycle-boundary tests passed; the Recheck fixture now explicitly supplies
unlocked display providers so its evidence classification is deterministic. The destroy/fault
class retains 4 adjacent fault/cancellation RED tests. The complete
`connectedFullDebugAndroidTest` run reached 87 tests with 28 failures: 1 debug package-id
fixture, 2 callback-flush, 4 destroy fault/cancellation, 6 long-boundary, 14 recheck/visibility,
and 1 Guardian lifecycle failure; the virtual-doze wake test is green. These remain residual
regression evidence rather than ticket15 closure failures; the ticket15-specific deterministic
tests are green.

### 6. Phase 0 RED contract → future invariant mapping

| Ticket | Phase 0 RED evidence | Phase 1/2에서 green이 되어야 하는 invariant |
| --- | --- | --- |
| 02 | 30초 이상 추가시간 뒤 stale/empty/null window와 no event에서 evaluator/warning/persisted outcome이 빠지고, 다른 nonessential root 전환은 이전 package를 잠그면 안 됨 | raw `ForegroundFacts`를 `ForegroundEvidenceModule`의 동일 contract로 분류하고, R5 A bounded fallback의 session effect와 decision permission을 sealed outcome으로 함께 전달한다. fallback은 evidence validity를 갱신하지 않는다. |
| 03 | latest refresh 뒤 delayed stale settings publication이 final snapshot, generation, visible outcome을 stale 값으로 되돌림 | `ConnectionScopedSourceOrderSequencer`가 refresh receiver와 settings collector의 관찰 시점에 `SourceOrderIdentity`를, runtime publication에는 추가로 `RuntimeRevision`을 발급한다. 하나의 `SerializedDecisionWorker`만 current `AcceptedRuleRuntimeSnapshot`을 교체하며 connection/`LifecycleGeneration`을 검증한 뒤 accepted revision보다 newer인 publication만 수락하고 stale publication과 그 visible/effect 결과를 publish하지 않는다. |
| 04 | wall clock boundary가 virtual doze 중 지나가고 scheduler clock은 멈춘 상태에서 wake recovery가 없거나 늦어지며, sleep 중 usage mutation이 없어야 함 | worker가 wall-to-monotonic delay와 semantic deadline을 계산하고 wake/USER_PRESENT에서 현재 wall time을 재계산하며, private scheduler adapter는 delay를 post/cancel하고 wake만 보고한다. R5/AR005 decision은 wake 후 budget 안에 나오고 sleep interval을 session으로 소급하지 않는다. |
| 05 | callback이 delayed persistence를 기다리고, separate decision call이 flush commit 전에 evaluator를 읽을 수 있음 | `submit`은 nonblocking이고 `SerializedDecisionWorker`가 visible reconciliation → commit → decision을 단일 serialized owner로 실행한다. persisted outcome은 commit 완료 후에만 evaluator가 읽는다. |
| 06 | destroy 중 evaluator/notification/handler가 in flight이고, fault/cancellation 뒤 next event와 durable reconnect recovery가 불명확함 | numeric selection 전 production은 `RecoveryOnlyStop`으로 즉시 lifecycle/`LifecycleGeneration`을 invalidate하고 post-destroy outcome을 suppress하며 scheduler/worker work를 cancel한 뒤 unfinished durable state를 reconnect recovery 대상으로 남긴다. 이 mode에는 `TotalDrainDeadline`이나 drain completion guarantee가 없다. later explicit numeric selection 뒤에만 `DeadlineDrainStop`과 `DrainResult.completed`/`timedOut` contract를 production에서 사용한다. 모든 suspend-path `CancellationException`은 request child 밖으로 재전파한다. worker-scope cancellation은 현재 lifecycle generation을 종료하고 새 `LifecycleGeneration`/reconnect에서만 recovery하며, request-child cancellation은 worker scope가 살아 있는 동안 해당 request만 side effect/outcome 없이 취소하고 같은 generation의 다음 request를 살린다. ordinary fault는 같은 worker의 다음 request를 살리며, durable session history는 policy replacement나 취소 때문에 다시 쓰지 않고 destroy 뒤 side effect는 0건이며 durable state는 reconnect에서 복구된다. |

이 mapping은 Phase 0 RED 테스트를 삭제하거나 약화시키라는 뜻이 아니다. Phase 1/2의 contract
test는 같은 value-level interface를 통과해 RED assertion을 green으로 만들고, 제품 동작
regression은 기존 evaluator/session meaning을 별도로 계속 검증한다.

### 7. Deletion test와 module 가치

| Deleted module | Complexity that reappears | Depth / leverage / locality judgment |
| --- | --- | --- |
| `ForegroundEvidenceModule` | R1부터 R13의 stale, partial, null, essential, keyguard, split-screen, contradictory root와 TTL branch가 accessibility caller, retry callback, reconnect path, 테스트 fixture에 다시 복제된다. caller가 Android observation shape와 policy meaning을 동시에 알아야 한다. | 한 `classify` interface 뒤에 13행의 policy interpretation과 evidence state를 모으므로 depth가 있다. 모든 caller와 두 adapter가 같은 classification을 얻어 leverage를 받고, policy bug와 verification은 한 implementation과 한 contract suite에 모여 locality를 얻는다. |
| `SerializedDecisionWorker` | `AppUsageTracker`, accessibility host, rule blocker가 visible reconciliation, Room commit, evaluator, planner, generation guard와 side effect ordering을 각각 조합해야 한다. ticket 05의 separate queue race와 ticket 06의 destroy race가 caller마다 재발한다. | 한 `submit` handoff 뒤에 persistence, decision, scheduling, generation과 error containment를 숨기므로 depth가 있다. caller는 observation과 runtime만 알면 되고, 한 ordering fix가 모든 request에 leverage를 준다. commit/evaluator/destroy 지식이 한 implementation에 모여 locality를 얻는다. |

이 deletion test는 qualitative design gate다. 줄 수가 줄었는지나 private call order가 맞는지를
자동 판정하지 않는다. 실제 implementation에서 module을 지워도 위 복잡성이 N caller로
되돌아오는지 PR 또는 design record에서 다시 기록해야 하며, 복잡성이 돌아오지 않으면
shallow wrapper로 판단해 도입하지 않는다.

### 8. Explicit out of scope

- Room entity, DAO contract, database version, migration, usage ledger의 의미와 multi-process
  owner 변경.
- Gradle properties, version catalog, plugin, Kotlin/KSP version, dependency와 build 설정 변경.
- AIDL, exported API, public app response, guardian approval surface와 flavor capability gate의
  의미 변경.
- 사용일, active range, allowance, contributor, guardian extra time, skip, fail policy의 제품
  의미 변경. R5 A 외의 policy 선택은 별도 product approval이 필요하다.
- Device Owner, Lock Task, kiosk guarantee, best-effort protection 수준의 변경.
- Phase 3 per-package coordinator와 Phase 4 lifecycle host의 선제 도입. 각 조건이 재현되기
  전에는 unused interface와 pass-through host를 만들지 않는다.
- 이 ticket에서의 production code, Room, Gradle, exported API 또는 ticket status 변경.
- 샤오신 패드 프로 12.7 Android 16의 AR004 실제 검증과 reported incident 종료 판정. 이
  contract는 device evidence를 대신하지 않는다.

### 9. Decision table — approved architecture record

아래 표는 승인된 선택을 기록한다. 모든 행의 `Approval evidence`는 동일한 사용자 승인
기록을 가리킨다. 이전의 policy matrix `A` 응답은 이 architecture table의 승인 evidence가
아니며, 2026-09-01의 사용자 authorization은 same-reviewer rebuttal이 수렴한 뒤의
independent fresh Sol Medium reviewer와 parent의 D1부터 D9까지의 완전한 수렴을 근거로 한다.

| Decision | Approved option | Alternative | User impact | Evidence / rationale | Approval evidence |
| --- | --- | --- | --- | --- | --- |
| D1. evidence module seam | **A — 승인됨:** `ForegroundFacts`와 `ForegroundEvidencePolicySnapshot` 두 cohesive value만 받는 value-only `ForegroundEvidenceModule`의 단일 `classify(ForegroundFacts, ForegroundEvidencePolicySnapshot)`, 그리고 source의 production/deterministic 두 adapter | **B —** 현재 blocker caller에 policy branch를 유지하고 module seam을 만들지 않음 | A는 stale/partial 정책을 한 곳에서 유도하며 caller와 테스트를 단순하게 한다. module은 policy delivery state를 숨겨 갖지 않고, worker가 accepted snapshot을 명시적으로 주입한다. B는 초기 추출은 작지만 AR001/AR002의 policy branch와 reflection fixture가 여러 경로에 남는다. | 13행 policy, ticket 02, known responsibility concentration, DEEPENING의 deletion/depth 기준 | `APPROVAL-ARCH-2026-09-01` |
| D2. persistence/decision ownership | **A — 승인됨:** worker가 visible reconciliation, flush/commit, evaluator와 next-plan의 단일 serialized owner가 됨 | **B —** tracker가 Room writer를 계속 소유하고 별도 worker가 evaluator를 호출 | A는 ticket 05의 flush-before-decision invariant를 구조적으로 보장한다. B는 callback을 비동기화해도 두 queue의 순서를 다시 조정해야 하며 stale read 위험이 남는다. | ticket 05 RED result와 canonical Phase 2 ownership requirement | `APPROVAL-ARCH-2026-09-01` |
| D3. evidence와 scheduling 분리 | **C2 — 승인됨:** `ForegroundEvidenceModule`은 raw facts에서 value-only sealed classification, intrinsic evidence validity(`EvidenceValidity`, 예: `validUntil`)와 declarative `FollowUpKind`만 반환한다. public result에는 retry limit, backoff step, execution deadline, attempt, coalescing, generation, scheduler token 또는 callback operation을 넣지 않는다. `SerializedDecisionWorker`는 request마다 하나의 accepted immutable `AcceptedRuleRuntimeSnapshot`을 소유한다. private worker-internal `FollowUpPolicy`가 그 snapshot, follow-up kind, attempt와 runtime state에서 retry/backoff와 `ExecutionDeadline`을 도출하며, scheduler adapter는 derived plan만 post/cancel한다. `EvidenceValidity`와 `ExecutionDeadline`은 서로 다른 개념과 type이다. | **B —** evidence module이 concrete delay와 Handler/scheduler operation까지 반환 | C2는 evidence meaning과 execution scheduling을 분리하면서 worker가 request의 snapshot과 실행 순서를 함께 소유한다. module과 public result는 timing/execution state를 노출하지 않고, worker 내부에서만 runtime에 맞는 follow-up을 계산한다. B는 caller가 단순해 보이지만 Android timing과 retry state가 evidence interface에 결합된다. | ticket 02/04의 서로 다른 evidence와 clock 실패, DEEPENING의 seam discipline, evidence validity와 execution deadline의 분리 | `APPROVAL-ARCH-2026-09-01` |
| D4. runtime publication ordering | **A — 승인됨:** `ConnectionScopedSourceOrderSequencer`는 정확히 두 caller operation을 제공한다. non-publication event/synthetic wake는 `nextSourceOrderIdentity()`를 사용하고, settings/refresh publication은 `reserveRuntimePublication()`으로 `SourceOrderIdentity`와 `RuntimeRevision`을 같은 critical section에서 atomic pair로 예약한다. genuinely fresh connection에서 sequencer를 초기화하거나 reset한 직후에만 host와 worker의 initial accepted snapshot을 `RuntimeRevision(0L)` sentinel로 시작한다. same-lifecycle `ensureDecisionWorker`/replacement는 host의 current `RuleRuntime`과 `latestRuntimeRevision`을 atomically inherit하고 freshness를 낮추지 않는다. readiness 확인, host capture, 이전 worker stop, replacement construction과 installation은 private worker handoff lock으로 직렬화하며 `runtimeLock`을 worker stop과 함께 잡지 않는다. worker는 connection/`LifecycleGeneration`을 검증한 뒤 strictly newer `RuntimeRevision`만 `AcceptedRuleRuntimeSnapshot`으로 수락한다. 세 값은 서로 교환하거나 numerically compare하지 않는 distinct real value type이며 caller와 worker는 standalone publication revision을 allocate하지 않는다. | **B —** 현재 mutex critical section만 유지 | A는 ticket 03에서 재현된 latest→stale rollback, same-lifecycle worker recovery의 freshness rollback과 concurrent replacement의 stale seed installation을 차단한다. sequencer만 source/publication values를 발급하고 worker handoff owner가 readiness와 installation을 함께 선형화하므로 caller emission과 replacement가 accepted state를 되돌릴 수 없다. B는 lock mutual exclusion만 보장하고 stale emission 또는 replacement handoff의 identity를 보장하지 않는다. | ticket 03 deterministic interleaving과 AR010 known limitation | `APPROVAL-ARCH-2026-09-01` |
| D5. cancellation semantics | **A — 승인됨:** 모든 suspend adapter/evaluator/request code의 `CancellationException`은 request child 밖으로 재전파하고 nonfatal logging하지 않는다. lifecycle owner가 worker-scope/generation cancellation이면 현재 generation을 종료하고, request-child cancellation인데 worker scope가 살아 있으면 해당 request만 `CANCELED`로 표시해 side effect/outcome 없이 버린 뒤 같은 serialized generation이 다음 queued request를 처리한다. `CancellationException`이 아닌 ordinary error는 request 단위로 contain/log하고 같은 generation을 계속하며, non-coroutine callback은 별도 callback boundary containment를 따른다. | **B —** 모든 throwable을 nonfatal failure로 변환 | A는 cancellation의 scope를 보존하면서 ticket 06의 same-generation continuation과 새-generation recovery를 구분한다. B는 worker가 계속 살아 보일 수 있지만 cancellation을 crash처럼 기록하거나 정상 teardown을 지연시킬 위험이 있다. | repository instructions, ticket 06 two-level cancellation contract | `APPROVAL-ARCH-2026-09-01` |
| D6. total drain budget | **EXPLICIT_MEASUREMENT_DEFERRAL — 승인됨:** representative persistence/evaluator/scheduler cleanup latency와 durable reconnect recovery를 측정하고, 그 뒤 명시적으로 하나의 numeric end-to-end budget을 선택한다. 그 전 production은 `RecoveryOnlyStop`만 사용하며 `TotalDrainDeadline`이나 drain completion guarantee 없이 즉시 lifecycle/generation invalidation, post-destroy outcome suppression과 cancellation을 수행하고 unfinished durable state를 reconnect recovery 대상으로 남긴다. `DeadlineDrainStop`과 `DrainResult.completed`/`timedOut`은 later explicit numeric selection 뒤 production에서만 허용한다. candidate deadline injection은 deterministic tests/measurement 전용이다. | **NUMERIC_SELECTION_NOW —** 대표 측정 전 2초 또는 5초를 production budget으로 선택 | 숫자를 지금 고정하지 않아 teardown의 최종 운영 threshold는 뒤로 미루지만 안전한 invalidation/suppression/recovery와 측정 instrumentation은 먼저 만든다. 2초와 5초는 candidate test/measurement input일 수 있으나 승인된 production default나 현재 선택이 아니다. | ticket 06의 outer/단계별 대기는 total contract를 확정하지 못한다. 대표 persistence/evaluator/scheduler cleanup, fault/reconnect 조건을 측정하고 explicit later numeric selection을 남긴다. | `APPROVAL-ARCH-2026-09-01` |
| D7. performance acceptance | **A — 승인됨:** callback nonblocking을 hard invariant로 두고 representative p95 callback/decision latency를 구현 후 기록하며 새 p95 숫자나 threshold는 지금 발명하지 않음 | **B —** architecture approval에서 고정 p95 숫자까지 제품 threshold로 결정 | A는 ticket 05가 입증한 callback blocking 제거를 보장하면서 측정 전 가짜 정밀도를 피한다. B는 운영 목표를 조기에 고정하지만 세션 크기와 device evidence 없이 잘못된 목표가 될 수 있다. | ticket 05 RED contract와 canonical Phase 2 performance criterion | `APPROVAL-ARCH-2026-09-01` |
| D8. adapters와 internal seams | **A — 승인됨:** framework facts seam과 worker outcome seam 각각에 production/deterministic adapter pair를 두고 persistence, clock, scheduler와 follow-up policy는 worker-internal private seam으로 유지한다. 기존 repository abstraction은 제거하지 않고 persistence implementation에서 계속 보존한다. | **B —** current internal provider lambda와 reflection field를 public contract로 승격 | A는 facts와 outcomes라는 두 외부 variation을 adapter pair로 증명하면서 public surface를 value-only로 유지한다. persistence, clock, scheduler와 follow-up policy는 worker 내부에 남겨 execution detail을 외부에 고정하지 않는다. B는 테스트는 쉬워질 수 있으나 implementation detail을 caller와 장기 contract에 고정한다. | DEEPENING의 “two adapters means a real seam”과 Phase 0 temporary seam evidence, 기존 repository abstraction 보존 | `APPROVAL-ARCH-2026-09-01` |
| D9. conditional modules | **A — 승인됨:** Phase 3 coordinator와 Phase 4 lifecycle host는 trigger가 재현될 때만 별도 approval과 ticket으로 시작 | **B —** 지금 worker와 함께 coordinator/host를 미리 만든다 | A는 unused shallow layer와 조건부 ticket을 피한다. B는 미래 변경을 미리 준비하지만 현재 증거 없이 scheduling/lifecycle ownership을 넓힌다. | canonical Phase 3 no-go와 Phase 4 trigger rules, prior reviewer conclusion | `APPROVAL-ARCH-2026-09-01` |

**Approval record — explicit user authorization:**

- `Approval record ID:` `APPROVAL-ARCH-2026-09-01`
- `Architecture decision:` `APPROVED`
- `Selected options:` `D1=A; D2=A; D3=C2; D4=A; D5=A; D6=EXPLICIT_MEASUREMENT_DEFERRAL; D7=A; D8=A; D9=A`
- `Approved by:` `User, explicitly authorizing implementation to continue after the same-reviewer rebuttal converged and the independent fresh Sol Medium reviewer and parent fully converged on all nine decisions`
- `Approved at:` `2026-09-01`
- `Authorization basis:` `The user authorized continuation once the same-reviewer rebuttal converged; that convergence is now complete for D1-D9.`
- `Tradeoffs accepted:` `The approved architecture concentrates evidence interpretation in a value-only module, serialized persistence/decision ordering and execution policy in the worker, and keeps facts/outcomes as the public external seams. It defers the numeric drain choice until representative measurement while permitting safety plumbing, invalidation, suppression, recovery, instrumentation and deterministic candidate-budget tests.`
- `Drain budget:` `EXPLICIT_MEASUREMENT_DEFERRAL — no 2-second or 5-second production default before representative measurement and explicit later numeric selection`

이 section은 위 approval record에 따라 `APPROVED`로 유지한다. ticket 08의 evidence contract와
adapter implementation은 이 승인된 contract를 따라 시작할 수 있다. D6의 numeric drain choice는
측정과 explicit later selection 전까지 구현에서 추측하지 않는다. 그 전 production은
`RecoveryOnlyStop`으로 deadline/completion guarantee 없이 invalidation, suppression, cancellation과
recovery를 수행한다. candidate deadline은 test/measurement에만 주입하고, later explicit numeric
selection 뒤의 `DeadlineDrainStop`만 하나의 total deadline을 가진다.

### Phase 1 — foreground evidence deep module

**목적:** 이 section 앞의 architecture contract, 특히 §§1.0–1.1과 §§4–6을 실행해 raw
framework facts와 evidence policy 해석을 하나의 deep module 뒤에 둔다. 아래는 실행 순서이며,
그 contract를 다시 정의하지 않는다.

1. 승인된 D1, D3, D8을 §§1.0–1.1대로 실행해 `ForegroundFacts`,
   `ForegroundEvidencePolicySnapshot`, `classify(ForegroundFacts,
   ForegroundEvidencePolicySnapshot)`와 canonical sealed outcome interface를 구현한다. module
   result는 classification, intrinsic `EvidenceValidity`와 declarative `FollowUpKind`만
   반환하며, §1.1에서 금지한 retry/backoff/execution state를 추측해 추가하지 않는다. policy
   snapshot의 현재 소유와 source-order replacement/stale rejection은 §1.2와 §5대로 worker에만 둔다.
2. framework 조회와 event copy/recycle은 §4의 production source adapter/callback에 두고,
   관찰 시점에 source-order identity를 할당한 뒤 immutable value facts를 만든다. value seam 전에
   event를 모든 경로에서 무조건 recycle한다. deterministic source adapter도 같은 value contract와
   contract suite를 통과시킨다. 관찰 입력에는 policy-derived package set, TTL/eligibility, retry
   attempt나 concrete deadline을 추가하지 않는다.
3. Phase 0 ticket 02와 04의 evidence mapping은 §6의 동일 interface를 통과하는 contract test로
   green으로 바꾸고, 기존 evaluator/session 의미는 별도 regression으로 확인한다.
4. caller는 raw facts를 전달하는 얇은 역할로 남기며, deletion test와 module의 depth/leverage/
   locality 판단은 §7에 따라 PR 또는 design record에 기록한다.

**완료 조건:**

- [ ] §1.1의 raw-facts-only input, intrinsic `EvidenceValidity`, declarative `FollowUpKind`와
      coherent sealed outcome contract를 통과해 Phase 0 evidence mapping이 green이다.
- [ ] §4의 production/deterministic source adapter pair가 같은 contract suite를 통과하고,
      public value contract에 Android/Room/Handler type이 0개다.
- [ ] §2, §5와 §7의 ownership, invariant, deletion/design 근거가 구현 기록에 연결되어 caller에
      policy branch와 private call-order 의존이 중복되지 않는다.
- [ ] node traversal와 value seam 이전 event recycle 조건은 §5와 기존 Accessibility safety
      contract에 맞게 검증된다.
- [ ] AR 004의 실제 Android 16/OEM 검증 결과가 기록됐다. 대상 기기를 사용할 수 없으면 해당
      검증을 미실행으로 표시하고 green 결과로 보고하지 않는다.

### Phase 2 — async decision worker

**목적:** 이 section 앞의 architecture contract, 특히 §§1.2–5를 실행해 callback의 handoff와
session/evaluator/scheduler/outcome publication 순서를 하나의 deep worker가 소유하게 한다.
아래는 실행 순서이며, normative ordering과 error contract는 앞 section만 따른다.

1. 승인된 D2, D4, D5, D6, D8을 §1.2대로 실행해 `SerializedDecisionWorker`를 구현한다.
   worker는 public `DecisionOutcomeSink` collaborator를 필수로 받고, production caller와
   deterministic test caller가 각각 §4의 adapter를 통해 같은 outcome seam을 사용한다. 각 request에
   대해 worker가 하나의 immutable `AcceptedRuleRuntimeSnapshot`을 수락하고 그 snapshot을 request 전체에서
   유지한다.
2. `AppUsageTracker`와 accessibility host는 §2–3에 정의된 raw facts/runtime handoff만 수행한다.
   production adapter/callback은 관찰 시점의 source-order identity를 보존한 immutable value facts를
   만든 뒤 value seam 전에 framework event를 무조건 recycle하며, worker request/queue에는 framework
   object를 넣지 않는다. 따라서 rejected request는 framework cleanup이 아니라 value-only request
   drop만 수행한다.
3. Phase 0 ticket 05와 06의 flush, fault, cancellation, destroy/reconnect mapping은 §6의
   worker interface와 public outcome sink를 통과해 green으로 바꾼다. persistence, evaluator,
   session reconciliation은 별도 queue로 분리하지 않는다.
4. private `FollowUpPolicy`는 §1.2와 §5에 따라 accepted `AcceptedRuleRuntimeSnapshot`, `FollowUpKind`,
   private attempt와 runtime state에서 `DerivedFollowUpPlan`을 계산한다. scheduler adapter는
   derived plan만 post/cancel하고 wake를 value로 보고한다. D6의 explicit budget/deadline plumbing,
   하나의 absolute `TotalDrainDeadline`, immediate invalidation, post-destroy outcome suppression,
   durable recovery, instrumentation과 deterministic candidate-budget tests는 numeric selection 전에도
   구현할 수 있으며, 2초나 5초를 production default로 사용하지 않는다.
5. shared source를 건드린 결과는 기존 regression과 §5의 lifecycle/generation 조건을 확인하고,
   필요한 flavor compile과 실제 기기 검증 여부를 기록한다.

**완료 조건:**

- [ ] §1.2의 submit/stop와 public `DecisionOutcomeSink` contract를 통해 worker 결과를
      deterministic adapter와 production adapter에서 동일하게 관찰한다.
- [ ] §2–3의 serialized ordering과 §5의 persistence/evaluator, generation, side-effect invariant가
      외부 outcome와 persisted result로 검증된다.
- [ ] §5의 ordinary exception containment와 두 level의 `CancellationException` contract를 각각
      검증한다. worker-scope cancellation 뒤 recovery/다음 event는 새 lifecycle
      generation/reconnect에서만 발생하고, request-child cancellation 뒤에는 같은 generation이
      다음 queued request를 처리하며 취소된 request의 side effect/outcome은 0건이다.
- [ ] §4–5의 private follow-up policy/scheduler ownership과 D6 measurement-deferral evidence를
      기록한다. representative latency/recovery instrumentation과 deterministic candidate-budget
      tests가 있고, 2초/5초 중 하나를 측정 전 production default로 사용하지 않는다.
- [ ] §6의 ticket 05/06 mapping과 기존 evaluator/session regression이 green이고, shared source에
      영향을 주면 세 flavor compile 결과가 기록됐다.

### Phase 3 — 조건부 per-package recheck coordinator

**목적:** 독립적인 package deadline이 실제로 남을 때만 scheduling 복잡성을 깊은 module로
   집중한다. Phase 2만으로 충분하면 이 phase를 실행하지 않는다.

**진입 조건 — 다음을 모두 확인할 때만 시작한다:**

1. split-screen 또는 다른 동시 visible package가 서로 다른 allowance/skip/schedule/use-day
   boundary를 갖고, 하나의 worker tick으로 허용 지연 안에 모두 재평가할 수 없다는 red test가
   있다.
2. planner가 package별로 독립적인 next action을 내고, Phase 2 worker의 단일 wakeup/coalescing
   정책이 한 package의 deadline을 잃는다는 재현 또는 측정이 있다.
3. foreground evidence policy와 generation semantics가 Phase 0, 1, 2에서 확정돼 coordinator가
   미결정 정책을 떠맡지 않는다.

**no-go 조건 — 하나라도 해당하면 coordinator를 만들지 않는다:**

- line count, 명명, 테스트 편의를 위한 shallow wrapper만 남는다.
- 한 package만 보장하는 제품 정책으로 확정됐거나, worker heartbeat가 모든 deadline을 검증된
  지연 안에 처리한다.
- 독립 deadline을 잃는 red test가 없거나, policy/visibility evidence가 아직 UNKNOWN이다.
- 새 Room/schema, Device Owner, Gradle 설정 또는 exported API 변경이 필요하다.
- production과 deterministic scheduler라는 두 adapter를 만들 수 없어 seam이 가설에 그친다.

**진입했을 때의 순서:**

1. package key, generation, due time, retry attempt, visibility result만 받는 작은 interface를
   정한다. coordinator는 activity나 Room을 직접 호출하지 않는다.
2. package별 job을 coalesce하고, 한 package를 교체하거나 취소해도 다른 package의 job을
   제거하지 않는다. unknown/null/exception retry와 long boundary recovery를 coordinator
   implementation에 둔다.
3. production scheduler `adapter`와 virtual-clock scheduler adapter를 제공하고, deep sleep,
   screen/user-present, refresh, destroy/reconnect에서 모든 key가 정리되는지 검증한다.
4. worker에 recheck request를 보내고 worker 결과로만 다음 job을 갱신한다. 오래된 generation의
   callback은 결과와 재예약을 모두 무시한다.

**완료 조건:**

- [ ] 진입 조건의 독립 deadline red test가 green이며, package A/B 각각의 denial과 recovery를
      외부 결과로 검증한다.
- [ ] 한 package의 cancel/replace, unknown retry, handler post failure가 다른 package의
      deadline을 삭제하지 않는다.
- [ ] production/deterministic scheduler adapter가 있고, doze와 reconnect 후 boundary가
      허용 지연 안에 회복된다.
- [ ] coordinator interface가 scheduler 복잡성을 숨기는 deep module이고, worker와 blocker에
      scheduling 세부사항이 중복되지 않는다.
- [ ] no-go라면 그 근거와 유지할 내부 구현을 known-limitations 문서에 남기고, 반쪽짜리
      coordinator나 unused interface를 커밋하지 않는다.

### Phase 4 — lifecycle host는 보류하고 재방문 조건만 관리한다

**목적:** 이미 독립 cleanup과 generation guard가 있는 상태에서 pass-through lifecycle host를
   조기에 만들지 않는다. 실제 반복이 확인될 때만 깊이를 입증한 뒤 추출한다.

**재방문 trigger:** 다음 중 하나가 재현될 때만 설계를 시작한다.

- feature 하나의 cleanup exception이 다른 feature의 cleanup 또는 AppRuleBlocker의 예약 취소를
  다시 건너뛴다.
- 두 번째 독립 scheduler feature가 생겨 동일한 setup, destroy, generation, receiver 규칙을
  복사하고 그 복사가 회귀를 만든다.
- destroy/setup race instrumentation이 guard 이후에도 callback, worker, notification의
  side effect를 관찰한다.
- reconnect ownership을 한 곳에 모으지 않으면 서로 다른 feature가 오래된 service reference
  또는 receiver를 유지한다는 증거가 있다.

**trigger가 없을 때:** lifecycle host를 만들지 않고, 보류 이유와 다음 재방문 조건을
known-limitations 문서에 기록한다.

**trigger가 있을 때의 순서:**

1. 두 개 이상의 실제 caller가 공유하는 lifecycle invariant를 먼저 테스트로 고정한다.
2. host `interface`는 setup, ready, invalidate, destroy의 의미와 ordering/error mode만 제공하고,
   feature별 action을 무차별 registry로 노출하지 않는다.
3. 각 feature의 cleanup은 여전히 독립 containment한다. host가 한 action의 실패로 나머지를
   중단하지 않는지 fault-injection test로 확인한다.
4. lifecycle host가 복잡성을 N caller에서 한 implementation으로 이동시키는지는 qualitative
   deletion/design gate로 PR 또는 design record에 기록한다. 자동 완료 판정으로 취급하지
   않으며, 그렇지 않으면 shallow layer로 판단해 되돌린다. host를 도입하는 경우 public
   contract의 Android/Room/Handler type 0개, `adapter → module` dependency 방향, production과
   deterministic adapter의 동일 contract suite, caller의 중복 policy branch 부재를 보조
   기준으로 확인한다.

**완료 조건:**

- [ ] trigger의 재현 test와 host 도입 이유가 known-limitations 문서에 연결돼 있다.
- [ ] trigger가 없으면 새 lifecycle module 없이 defer 결정을 기록했다.
- [ ] host를 도입하면 setup/reconnect/destroy/failure containment의 모든 호출자가 test되고,
      post-destroy side effect가 없다.
- [ ] host interface가 caller의 lifecycle 사실을 최소화하면서 depth, leverage, locality를
      제공한다. 단순 pass-through면 도입하지 않는다.
- [ ] host를 도입한 경우 deletion/design gate와 위 보조 기준을 PR 또는 design record에
      기록했다. 이 기록은 qualitative 근거이며 private call order 자동 검증 결과가 아니다.

## 검증 명령과 flavor 매트릭스

PowerShell에서 Gradle을 실행하기 전에 프로젝트 지침의 JBR을 설정한다.

```powershell
$env:JAVA_HOME = 'C:\Users\DELL\.jdks\jbr-21.0.11'
& "$env:JAVA_HOME\bin\java.exe" -version
```

phase별 최소 검증은 다음 순서로 수행한다.

```powershell
.\gradlew.bat testFullDebugUnitTest
.\gradlew.bat compileFullDebugAndroidTestKotlin
.\gradlew.bat assembleFullDebug
.\gradlew.bat assemblePlaystoreDebug
.\gradlew.bat assembleFdroidDebug
git diff HEAD --check
git diff --name-only c17677ae
git diff --cached --check  # staged 변경이 있을 때
```

실제 window enumeration, split-screen, keyguard, screen off/on, reconnect, OEM failure는
연결된 단일 기기에서 다음을 추가한다. 기기가 없으면 통과로 표시하지 않고 미실행 증거와
기기 검증 절차 링크를 남긴다.

```powershell
.\gradlew.bat connectedFullDebugAndroidTest
```

shared source, manifest, BuildConfig gate, service 또는 optional feature를 건드린 phase는
세 assemble 결과를 모두 요구한다. connected test는 JVM test를 대체하지 않으며, JVM test는
clock/window/scheduler adapter의 결정론을, instrumentation은 Android Accessibility 동작을
각각 증명해야 한다.

## 커밋 격리와 rollback

1. 각 phase는 `c17677ae` 또는 직전 phase의 green commit에서 별도 `codex/` branch로 시작한다.
   시작 전에 `git status --short`와 기준 commit을 기록하고, 기존 사용자 변경을 포함하지 않는다.
2. Phase 0 policy/test, Phase 1 evidence module, Phase 2 worker, 선택된 Phase 3 coordinator,
   Phase 4 host는 각각 독립 commit으로 만든다. no-go phase는 문서와 test evidence만 커밋하고
   빈 abstraction은 커밋하지 않는다.
3. 각 commit 전에 unstaged와 staged를 모두 확인한다: `git diff HEAD --check`,
   `git diff --name-only c17677ae`, 필요할 때 `git diff --cached --check`를 실행한다.
   허용되지 않은 Gradle 설정, Room/schema, flavor manifest, exported API가 나타나면 commit을
   중단하고 scope를 재승인한다. commit 후에는 별도로 `git diff --name-only c17677ae..HEAD`를
   실행해 기준 커밋 이후 실제 commit 범위를 확인한다.
4. phase 회귀 시 해당 phase commit만 `git revert <commit>`으로 되돌린다. destructive reset이나
   사용자 변경을 덮는 checkout/reset은 사용하지 않는다. 이전 phase의 green commit과
   known-limitations 기록은 보존한다.
5. 최종 handoff에는 phase별 commit, 통과한 명령, 미실행 기기 검증, no-go 결정과 남은 문제의
   known-limitations 링크를 함께 기록한다.

## 완료 정의

이 계획 전체는 다음을 모두 만족할 때만 완료다.

- Phase 0의 policy matrix와 red test가 있고, Phase 1과 Phase 2의 모든 완료 조건이 green이다.
- Phase 3은 진입 조건을 만족할 때만 구현되어 package별 경계를 검증하거나, no-go 근거를
  기록했다.
- Phase 4는 trigger가 없으면 defer를 기록하고, trigger가 있으면 host의 fault/lifecycle test가
  green이다.
- `refactor implementation complete`, `AR004 Android16 device verification complete`,
  `reported incident closed` 상태가 서로 분리되어 기록된다. 샤오신 패드 프로 12.7 Android 16
  증거 전에는 AR004와 incident를 닫지 않으며, 구현 완료를 OEM 해결 또는 release readiness로
  보고하지 않는다.
- known-limitations 문서에 남은 문제, 제품 선택, 기기별 미검증 범위가 최신 상태로 링크되어
  있으며 이 문서에는 그 세부 내용을 중복하지 않는다.
- full, playstore, fdroid의 필요한 build가 통과하고, 실제 기기 검증의 실행 여부가 정직하게
  보고된다.
- 기준 커밋의 앱 규칙 의미, 다중 프로세스 소유권, Accessibility safety, best-effort 보호
  한계가 유지된다.
