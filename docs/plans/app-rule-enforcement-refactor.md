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
- callback, receiver, worker, notification은 `setupReady`, `destroyed`, lifecycle generation,
  recheck generation을 모두 확인한다. destroy는 flag를 먼저 세우고 예약과 coroutine을 취소한다.

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

### Phase 1 — foreground evidence deep module

**목적:** Android 관찰값을 제품 판단으로 바꾸는 복잡성을 하나의 deep module 뒤에 둔다.

1. Phase 0 test가 요구하는 최소 surface를 기준으로 module의 `interface`를 설계한다. 입력은
   immutable observation과 clock/lifecycle 사실이고, 출력은 evidence 분류, package별 후보,
   다음 확인 시각과 이유다. Android `AccessibilityWindowInfo`, Room, Handler, activity launch를
   interface 밖으로 누출하지 않는다.
2. framework 조회와 root/window package 추출은 production `adapter`로 둔다. deterministic
   test adapter도 같은 interface를 만족시켜 정상, stale, null, partial, exception snapshot을
   주입한다. 두 adapter가 서로 다른 변화를 흡수하는지는 qualitative deletion/design gate로
   PR 또는 design record에 남기고, 자동 완료 판정으로 취급하지 않는다.
3. essential package filtering, 최근 event freshness, active root의 contradictory switch,
   empty/unknown snapshot, split-screen deduplication, keyguard와 overlay suspension을 module
   implementation 안으로 이동한다. `AppRuleBlocker`는 observation을 전달하고 결과를 실행하는
   얇은 caller로 남긴다.
4. module은 side effect 없이 결과를 반환한다. retry/backoff 자체가 evidence 정책인지 scheduling
   정책인지 구분하고, 한 module이 두 의미를 섞어 shallow interface가 되지 않게 한다.
5. Phase 0 red test를 interface를 통과시켜 green으로 바꾸고, 기존 evaluator와 session 의미를
   변경하지 않았음을 확인한다.

**완료 조건:**

- [ ] evidence policy의 모든 Phase 0 행이 module의 interface test로 green이다.
- [ ] production과 deterministic 두 adapter가 있고, Android/Room/Handler 의존성은 seam
      안쪽 implementation에만 있다.
- [ ] caller가 알아야 할 사실이 evidence 결과와 다음 action으로 제한되어 module에 depth가
      있다. interface가 내부 판단 규칙을 그대로 재노출하는 shallow pass-through가 아니다.
- [ ] deletion test는 qualitative design gate로 PR 또는 design record에 기록한다. 자동으로
      Phase 완료를 막는 결과로 사용하지 않으며, module이 제공하는 leverage와 변경 locality를
      설명한다.
- [ ] 보조적으로 public contract가 Android, Room, Handler type을 0개 노출하고, dependency
      방향이 `adapter → module`이며, production과 deterministic adapter가 같은 contract test
      suite를 통과하고, caller에 정책 branch가 중복되지 않는지 확인한다.
- [ ] `onAccessibilityEvent`에서 node traversal가 없고, 모든 copied event/root가 예외 경로를
      포함해 recycle된다.
- [ ] AR 004의 실제 Android 16/OEM 검증 결과가 기록됐다. 대상 기기를 사용할 수 없으면 해당
      검증을 미실행으로 표시하고 green 결과로 보고하지 않는다.

### Phase 2 — async decision worker

**목적:** main looper의 callback은 관찰과 wakeup만 하고, session flush와 rule decision은
   직렬화된 worker가 수행하게 한다.

1. event, visible package set, evidence result, runtime generation, reason을 포함하는 작은
   request 형태를 정한다. event가 필요하면 worker queue에 복사본만 넣고 소유권과 recycle
   규칙을 interface에 적는다.
2. 현재 `AppUsageTracker.onEvent()`가 소유한 visible-session reconciliation과 그 Room writer의
   경계를 먼저 명시한다. `onEvent()`는 immutable visible-set observation을 한 번의 serialized
   handoff로 넘기고, handoff 이후의 reconciliation writer와 decision worker는 하나의 직렬화된
   실행 경로에서 소유한다. 이전 visible session flush와 current-use-day persistence가
   commit 완료된 뒤에만 evaluator와 `nextPlan`을 실행한다. tracker queue와 decision queue를
   따로 두어 순서를 나누지 않는다.
3. Handler는 timer callback을 worker에 dispatch하고, worker 결과 중 activity launch나
   notification 같은 main-thread side effect만 generation 재확인 후 게시한다. wall-clock 경계를
   monotonic delay로 변환하고 wake 시 현재 시각으로 다시 계산하는 scheduler contract를 함께
   고정한다. callback 전체, synthetic event 생성/설정/recycle, scheduler post 실패를 containment한다.
4. DataStore collector와 refresh receiver의 snapshot publication을 한 serialized path로
   유지한다. 빠른 broadcast가 오래된 snapshot, override, reset generation을 되돌리지 않게
   generation 또는 equivalent ordering을 worker input에 포함한다.
5. worker cancellation, service destroy, reconnect를 테스트한다. `CancellationException`은
   정상 취소로 전달하고 나머지는 nonfatal logging 후 다음 event가 처리되게 한다.

**완료 조건:**

- [ ] worker가 수행하는 모든 Room/evaluator 작업이 Handler/main looper 밖에서 실행된다는
      thread assertion이 있다.
- [ ] event 전환에서 flush 완료 후 decision이 실행되고, 그 순서가 persisted outcome test로
      확인된다.
- [ ] `AppUsageTracker.onEvent()`의 visible-session reconciliation/Room writer에서 worker로의
      ownership handoff가 명시되고, 하나의 serialized path에서 flush commit 완료 후 evaluator가
      실행된다. 별도 tracker queue와 decision queue 사이의 순서에 의존하지 않는다.
- [ ] callback/worker/persistence/evaluator 오류를 주입해도 service가 다음 event를 처리하며
      activity가 중복 실행되지 않는다.
- [ ] refresh burst, setup/reconnect, destroy race에서 최신 generation만 결과를 publish한다.
- [ ] virtual doze와 wake에서 wall-clock 경계를 다시 계산하고, 수면 중 사용을 소급하지 않는
      결과가 확인된다. AR 005의 scheduler 선택과 허용 지연이 기록된다.
- [ ] in-flight decision의 destroy 시 cancellation과 bounded drain 결과가 측정되고, 남은 작업은
      명시된 recovery 경로로만 이어진다. lifecycle host 도입은 Phase 4 trigger가 있을 때만 한다.
- [ ] Phase 0와 기존 evaluator/session regression test가 green이고, 세 flavor compile에
      영향을 주는 shared source라면 세 flavor compile 결과가 기록됐다.

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
