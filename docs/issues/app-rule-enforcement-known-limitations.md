# 앱 규칙 재검사의 알려진 한계와 미해결 항목

기준 커밋은 `c17677ae` (`fix: enforce app rules across time boundaries`)이다. 이 문서는 해당 커밋 이후에도 남아 있는 결함, 결정 대기 사항, 테스트 공백, 수용된 플랫폼 한계와 구조 및 성능 부채의 단일 정본이다. 실행 순서와 변경 계획은 [앱 규칙 재검사 리팩토링 계획](../plans/app-rule-enforcement-refactor.md)을 따른다. connected-suite total과 failed-case owner의 정본은 [canonical baseline and inventory](app-rule-enforcement-baseline.md)다.

요구사항의 원문은 [앱 규칙 개편 요구사항](../requirements/app-rules.md), 동작 스펙은 [앱 규칙 스펙](../specs/app-rules-github-issue.md), 현재 사용일 세션 저장 결정은 [ADR 0002](../adr/0002-store-current-use-day-foreground-sessions.md), 기기 보호 수준 결정은 [ADR 0003](../adr/0003-use-best-effort-device-protection.md), 용어는 [CONTEXT.md](../../CONTEXT.md)를 참조한다. 이 문서는 그 내용을 복사하지 않고, 현재 구현에서 확인된 후속 작업만 기록한다.

## 상태와 심각도

- `확정 미해결`: 현재 코드 경로와 조건이 확인됐고 수정되지 않았다.
- `결정 대기`: 구현 전에 제품 정책을 정해야 한다.
- `재현 필요`: 가능한 위험이지만 현재 커밋에서 재현 증거가 없어 결함으로 확정하지 않는다.
- `잔여 위험`: 동작 또는 플랫폼 제약은 확인됐지만 영향이 조건부이거나 허용된 지연 안의
  사용자 실패가 아직 확정되지 않았다.
- `테스트 공백`: 구현이 맞다고 말할 근거가 부족하다.
- `수용된 한계`: 제품 결정과 ADR로 범위를 벗어난다.
- `계획된 부채`: 기능 오류는 아니지만 다음 구조 개선에서 해결해야 한다.
- `닫힘`: 기준 커밋에서 수정됐고 현재 열린 회귀 증거가 없다.

심각도 `P1`은 제한을 영구히 놓치거나 안전한 동작을 보장할 수 없는 경우, `P2`는 지연, 회귀 위험 또는 운영 비용이 있는 경우를 뜻한다. `수용된 한계`에는 우선순위를 붙이지 않고 `해당 없음`으로 표기한다.

## 상태 분리와 release gate

구현 완료, 실제 기기 검증 완료, 보고된 incident 종료는 서로 다른 상태다. `c17677ae`의
구현과 자동화 검증 결과를 `refactor implementation complete`로 기록하더라도, 그것은 OEM
동작을 해결했다는 뜻이 아니다. 대상 `Xiaomi Pad Pro 2025 12.7` Android 15 또는 Android 16의 실제 증거가 없는
현재 상태에서는 다음 두 상태를 닫지 않는다.

| 상태 | 현재 상태 | 완료 조건 |
| --- | --- | --- |
| `refactor implementation complete` | `별도 판정` | 코드와 자동화 검증의 completion criteria로만 판정한다. 기기 검증이나 incident 종료를 포함하지 않는다. |
| `AR004 Xiaomi Pad Pro 2025 12.7 device verification complete` | `열림` | Android 15 또는 Android 16 대상 기기에서 정책표의 시나리오와 계측 증거를 남긴다. |
| `reported incident closed` | `열림` | AR004 증거와 보고된 재현 조건의 해결 확인을 모두 남긴다. |

AR004 또는 incident가 열린 동안에는 OEM 해결이나 release readiness를 주장하지 않는다. 구현
완료와 현장 검증 및 incident 종료의 상태 변경은 각각 별도로 기록한다.

## 확정 미해결 결함

### AR 001 장시간 보호자 추가시간 뒤 가시성 증거 만료

- **Status / Severity:** `닫힘 (T21 deterministic contract; OEM gate remains separate)` / `P1`
- **Exact trigger:** 앱 규칙의 직접 사용 가능 시간이 소진된 상태에서 보호자 추가시간이 5초보다 길게 남아 있고, 앱이 계속 보이는 동안 새로운 `TYPE_WINDOW_STATE_CHANGED` 이벤트가 오지 않는다. 동시에 `service.windows`가 앱을 누락한 오래된 목록을 반환하거나 빈 목록 또는 `null` root를 반환하고, 다른 신뢰할 수 있는 비필수 앱 root도 없다. Canonical connected evidence는 이 trigger family의 six failed cases를 T21 owner로 기록한다.
- **Current behavior:** 승인된 R5 A 경로는 실제 event의 raw signal history를 host scheduler classifier에 보존하고, evidence age가 만료된 뒤에도 세 번의 bounded retry가 끝나면 마지막 비필수 package에 대해 `EVALUATE_FAIL_CLOSED`를 worker로 전달한다. worker는 synthetic event로 evidence TTL을 갱신하지 않은 채 boundary 시점의 persisted session을 읽고 evaluator 결과를 적용한다. `observeWorkerEvaluation()`은 synthetic request에서 evidence timestamp를 갱신하지 않지만 evaluator observation과 publication은 유지한다. evaluator의 ordinary failure는 applicable rule에 기존 fail-closed denial을 만들고, applicable rule이 없으면 evaluator-derived eligibility를 그대로 보존한다. request child cancellation은 outcome, warning, recovery 없이 contained된다. 다른 비필수 active root 또는 알려진 partial window가 현재 package를 식별하는 경우에는 이 fallback을 사용하지 않는다.
- **Impact:** T21의 deterministic contract에서는 시간 경계의 evaluator decision과 Guardian denial이 더 이상 사라지지 않는다. 이 결과는 `Xiaomi Pad Pro 2025 12.7` Android 15/16에서 같은 동작이 확인됐다는 뜻이 아니며, 실제 OEM window/root 동작과 reported incident 판정은 AR004에 남는다.
- **Evidence:**
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 `submitForegroundDecision`, `runScheduledRecheck`, worker handoff와 warning publication 경계
  - [SerializedDecisionWorker.kt](../../app/src/main/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorker.kt)의 `EVALUATE_FAIL_CLOSED` recovery와 evaluator cancellation containment
  - [AppRuleBlockerLongBoundaryRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerLongBoundaryRedTest.kt)의 T21 six-case deterministic scenarios
  - [canonical baseline inventory](app-rule-enforcement-baseline.md#t21-long-boundary-semantics--6-cases)의 T21 six-case observed failure set and closure evidence
- **Mitigation or decision needed:** 승인된 [foreground evidence policy matrix](../plans/app-rule-enforcement-refactor.md#foreground-evidence-policy-matrix--approved-2026-08-31)를 따른다. R5 A의 1.5초 bounded retry와 5초 evidence age는 그대로 유지한다. 다른 앱 root가 확실히 활성인 경우에는 이전 앱을 잠그지 않으며, current known partial window ownership은 T23 contract로 남긴다. Xiaomi 실제 adapter 검증은 ticket 29에서 수행한다.
- **Acceptance criteria:** T21의 가상 시각과 창 제공자 테스트는 30초 추가시간, stale/empty/null 또는 failed window, evaluator decision, persisted session read, denial payload, ordinary failure, cancellation과 healthy later event를 모두 확인했고 six inventory identifiers가 통과했다. 별도의 synthetic R5 회귀는 초기 real-event evidence timestamp가 synthetic evaluation 뒤에도 변하지 않음을 확인한다. `iPlay50_mini_Pro - 13` Android 13에서의 connected evidence는 deterministic closure 증거일 뿐 Xiaomi device acceptance를 대신하지 않는다.
- **Target refactor phase:** 리팩토링 계획의 `Phase 0 정책 결정`에서 결정했고 `Phase 1 foreground evidence 모듈`에서 수정했다. 실제 OEM 검증은 `AR 004`와 ticket 29에 남아 있다.

## 결정 기록과 구현 대기 사항

### AR 002 foreground evidence 정책표 승인 완료, T21/T22/T23 deterministic closure 완료

- **Status / Severity:** `T21/T22/T23 deterministic contract closure, 실제 기기 검증 잔여` / `P1`
- **Exact trigger:** 정상 접근성 이벤트, synthetic 재검사, 서비스 재연결, 화면 켜짐과 분할 화면에서 이벤트의 최신성, `service.windows`의 상태, `rootInActiveWindow`의 상태가 서로 다르게 관찰된다. 특히 이벤트가 만료된 뒤 창 목록이 대상 앱을 누락하거나 창 package가 비어 있는 조합에서 정책이 필요하다.
- **Current behavior:** `ForegroundEvidenceModule`이 raw facts와 approved policy를 분류하고, `SerializedDecisionWorker`가 accepted runtime, persisted reconciliation, evaluator와 outcome을 직렬화한다. T21의 expired event fallback은 host가 실제 event signal history를 유지하고 worker가 final fail closed decision을 수행한다. T22의 기존 keyed recheck scheduler는 delayed/changed evidence를 guarded re-evaluation으로 연결하고, stale replacement를 취소하며, package-less unknown slot의 기존 bounded retry sequence를 종료한다. T23의 기존 application-window selection, active-root fallback, provenance snapshot과 package ownership 경로는 11개 frozen case에서 approved contract를 충족했다. 실제 Xiaomi window/root adapter 동작은 ticket 29에 남아 있다.
- **Impact:** T21이 소유한 evidence age와 long-boundary decision 경로, T22가 소유한 recheck scheduling/retry/cancellation 경로, T23가 소유한 visibility/window/root/target ownership 경로와 T25가 소유한 Guardian repeated-denial lifecycle은 deterministic contract와 focused evidence로 닫혔다. 남은 제품 위험은 실제 Xiaomi window/root adapter 동작과 later callback, fixture 및 measurement work처럼 별도로 분류된 영역에 있다.
- **Evidence:**
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 foreground observation, host classifier history, worker handoff와 scheduler path
  - [ForegroundEvidenceModule.kt](../../app/src/main/java/neth/iecal/curbox/domain/apprules/ForegroundEvidenceModule.kt)의 approved R1–R13 classification
  - [SerializedDecisionWorker.kt](../../app/src/main/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorker.kt)의 serialized persistence/evaluation/publication path
  - [AppRuleBlockerLongBoundaryRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerLongBoundaryRedTest.kt)와 [ForegroundEvidenceContractTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/ForegroundEvidenceContractTest.kt)의 focused evidence
  - [canonical T22 closure evidence](app-rule-enforcement-baseline.md#ticket-22-closure-evidence--2026-09-10)의 three recheck identifiers and exact counts
  - [canonical T23 closure evidence](app-rule-enforcement-baseline.md#ticket-23-closure-evidence--2026-09-10)의 11 visibility/ownership identifiers, exact device identity and target/warning outcomes
  - [앱 규칙 요구사항: 전면 사용량 추적](../requirements/app-rules.md#L91)와 [앱 규칙 스펙: window reconciliation 결정](../specs/app-rules-github-issue.md#L93)
- **Mitigation or decision needed:** 승인된 표의 13개 행과 `AR002-R01`부터 `AR002-R13`까지의
  mapping을 유지한다. R5에는 승인된 A안을 사용하고 B는 오차단과 제한 누락의 tradeoff를
  기록한 비활성 대안으로만 남긴다. T22의 기존 scheduler/retry contract와 T23의 기존
  window/root/target ownership contract는 새 interval, policy 또는 threshold를 추가하지 않고
  보존한다. 실제 OEM evidence가 발견되면 ticket 29의 device gate에서 다룬다.
- **Approval evidence:** parent replied `A` on 2026-08-31, approving the full 13-row matrix and
  selecting R5 option A.

- **Acceptance criteria:** 승인된 정책표가 13개 행 모두에 `VISIBLE`, `NOT_VISIBLE`, `UNKNOWN`,
  최대 허용 지연, fail policy, 자동 테스트 mapping, 평가할 package, wait/retry, block/allow
  결정, evidence TTL 갱신 여부, rationale, owner와 approval evidence를 갖는다. 각 결과는
  `AR002-R01`부터 `AR002-R13`까지의 자동 테스트 mapping과 연결된다. 승인된 표가 [리팩토링
  계획](../plans/app-rule-enforcement-refactor.md#foreground-evidence-policy-matrix--approved-2026-08-31)과
  구현 module의 입력 및 출력 계약에 링크된다. 이후 `UNKNOWN`의 fail policy를 바꾸려면
  별도의 제품 승인을 다시 기록한다.
- **Target refactor phase:** `Phase 0 정책 결정`.

## 테스트 공백

### AR 003 결정적 장시간 경계 테스트 부재

- **Status / Severity:** `닫힘 (T21 deterministic harness; OEM gate remains separate)` / `P1`
- **Exact trigger:** 보호자 추가시간이 foreground evidence TTL보다 길고, allowance 경계에서 이벤트가 오지 않으며, 창 제공자가 오래된 목록, 빈 목록 또는 `null` root를 반환하는 경우다.
- **Current behavior:** `AppRuleBlockerLongBoundaryRedTest`는 `VirtualClock`, `VirtualRecheckScheduler`, ordered provider states와 worker/handler latches를 사용해 sleep 없이 T21의 30초 boundary를 재현한다. evaluator result, persisted read, denial payload, ordinary failure, cancellation과 later event를 관찰한다.
- **Impact:** T21 소유 영역에서는 callback이 실행됐다는 사실만으로 green을 주장하는 공백이 닫혔다. 실제 `windows`와 `rootInActiveWindow` 동작 및 Xiaomi 검증은 AR004에 남아 있다.
- **Evidence:**
  - [AppRuleBlockerLongBoundaryRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerLongBoundaryRedTest.kt)의 deterministic clock/scheduler and T21 six-case evidence
  - [ForegroundEvidenceContractTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/ForegroundEvidenceContractTest.kt)의 29 focused evidence-age/provenance cases
  - [SerializedDecisionWorkerTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorkerTest.kt)의 24 focused worker cases
  - [canonical baseline inventory](app-rule-enforcement-baseline.md#ticket-21-closure-evidence-2026-09-10)의 exact run counts and remaining failure owners
- **Mitigation or decision needed:** T21 harness와 production seam을 유지한다. T22 recheck scheduling/retry/cancellation은 closure evidence로 닫혔고, visibility ownership, callback flush와 device validation은 각각 T23, T24 및 T29의 scope로 남긴다.
- **Acceptance criteria:** T21의 six stable identifiers와 synthetic timestamp regression은 final focused connected run에서 통과했고, focused JVM에는 53 tests with 0 failures, full JVM에는 351 tests with 0 failures가 기록됐다. 실행별 connected 관찰은 다음과 같이 구분한다: `T21 closure execution (recorded 2026-09-10; exact XML timestamp not retained in this record)`은 92 tests, 89 passed, 3 failures였고 T24 2건과 T26 1건으로 분류됐다. `Ticket 22 execution (XML timestamp 2026-09-10T01:24:14; timezone not encoded)`은 92 tests, 88 passed, 4 failures였고 T24 2건, T25 1건, T26 1건으로 분류됐다. `Ticket 23 latest execution (XML timestamp 2026-09-10T01:56:03; timezone not encoded)`은 92 tests, 89 passed, 3 failures였고 T24 2건과 T26 1건으로 분류됐으며 T21, T22, T23, T25 failure node는 없었다. 이후 Ticket 25 focused class는 `2026-09-10T03:08:12`에 3 tests, 3 passed, 0 failures로 Guardian reuse, updated denial content, close broadcast와 background cleanup을 확인했고, Ticket 25 closure는 canonical baseline에 기록됐다. 이 수치들은 서로 다른 실행의 관찰이며, Ticket 22의 T25 failure는 historical execution-specific evidence로 유지하되 현재 closure를 부정하는 definitive product diagnosis로 사용하지 않는다.
- **Target refactor phase:** `Phase 0 deterministic harness`와 `Phase 1 foreground evidence 모듈`의 T21 범위를 완료했다. 이후 scheduler 관련 검증은 `Phase 2`에서 수행한다.

### Ticket 25 Guardian approval lifecycle closure

- **Status / Severity:** `닫힘 (deterministic same-instance and refreshed-screen contract; OEM gate remains separate)` / `P2`
- **Exact trigger:** 같은 denied app rule이 visible Guardian approval 화면에 반복 전달될 때 기존 화면이 중복 생성되거나, singleTop으로 재사용된 activity가 이전 denial rows를 계속 표시하는 경우다. Ticket 20의 frozen owner inventory에는 `GuardianApprovalActivityLifecycleTest::repeatedDenialReusesTheVisibleApprovalScreen` 한 건만 속한다.
- **Current behavior:** `AppRuleBlocker.createGuardianApprovalIntent()`는 기존 `NEW_TASK` handoff에 명시적인 `SINGLE_TOP`을 함께 사용한다. `GuardianApprovalActivity`는 package identity와 denial list를 하나의 validated payload로 확인한 뒤 valid `onNewIntent()`에서만 intent와 selected rule을 갱신하고 기존 choice rows를 한 번 교체하므로 같은 activity instance와 one approval flow를 유지한다. package가 비어 있거나 denial list가 비어 있거나 entry가 null이거나 `ruleId`가 blank인 repeated payload는 `super.onNewIntent()`와 `setIntent()` 전에 거부되어 현재 화면, intent와 close package를 유지한다. malformed initial payload는 안전하게 finish한다. `ruleName`과 `reason`의 기존 표시 동작은 유지한다. 기존 `onStop()` close notification과 receiver unregister cleanup도 유지된다.
- **Impact:** 반복 denial은 하나의 visible approval activity에서 최신 denial content를 보여주며 중복 choice row 또는 stale payload를 만들지 않는다. 이 deterministic closure는 Xiaomi Pad Pro OEM window/root 또는 reported incident 해결을 뜻하지 않는다.
- **Evidence:**
  - [GuardianApprovalActivity.kt](../../app/src/main/java/neth/iecal/curbox/ui/activity/GuardianApprovalActivity.kt)의 `onNewIntent()`, `readValidatedPayload()`와 single-render path
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 `createGuardianApprovalIntent()` reuse flags
  - [GuardianApprovalActivityLifecycleTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/ui/activity/GuardianApprovalActivityLifecycleTest.kt)의 frozen repeated-denial case와 close/background lifecycle tests
  - [canonical Ticket 25 closure evidence](app-rule-enforcement-baseline.md#ticket-25-closure-evidence--2026-09-10)
- **Mitigation or decision needed:** no further T25 policy decision is needed. Preserve the same-instance path, existing denial behavior, close broadcast and receiver cleanup. Keep T26 fixture repair, p95 measurement, drain-budget decision and Ticket 29 Xiaomi validation separate.
- **Acceptance criteria:** focused Guardian lifecycle connected tests pass `6/6`, covering valid refresh, null/malformed denial replacement, missing-package replacement, malformed initial finish, close broadcast and background finish; the valid repeated request has one activity instance, one approval choice row and updated denial content; malformed replacement retains the valid cleanup package; full JVM is `351/351`; and the latest full connected result is `95` tests with `94` passed and only T26's debug fixture remaining. These results are iPlay50 deterministic evidence, not Xiaomi acceptance.
- **Target refactor phase:** `Phase 1/2 lifecycle integration`; no new lifecycle host or approval architecture was introduced.

### AR 004 Xiaomi Pad Pro 2025 12.7 실제 창 동작 테스트 부재

- **Status / Severity:** `테스트 공백` / `P1`
- **Exact trigger:** `Xiaomi Pad Pro 2025 12.7` Android 15 또는 Android 16에서 앱을 계속 전면에 둔 채 보호자 추가시간 경계와 전체 앱 사용 금지 시간 경계를 통과한다. 새 window event가 없거나 OEM이 오래된 창 목록, 빈 목록, `null` root를 반환하는 상태를 포함한다.
- **Current behavior:** 현재 테스트는 `RecordingService`와 `applicationWindowSnapshotProvider`, `activeWindowSnapshotProvider` fake를 사용한다. split screen과 재연결 시나리오도 실제 `AccessibilityService.windows` 또는 `rootInActiveWindow`를 읽지 않는다.
- **Impact:** Android 15/16 또는 특정 OEM의 window provider 동작과 현재 fallback 정책이 맞는지 증명하지 못한다. iPlay50_mini_Pro 등 다른 기기에서 통과한 결과를 Xiaomi Pad Pro의 보고된 실패가 해결됐다는 증거로 사용할 수 없다.
- **Evidence:**
  - [AppRuleBlockerRecheckTest.kt:1067](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L1067)부터 [AppRuleBlockerRecheckTest.kt:1093](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L1093)의 fake reconnect test
  - [AppRuleBlockerRecheckTest.kt:1103](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L1103)부터 [AppRuleBlockerRecheckTest.kt:1131](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L1131)의 fake split screen test
  - [AppRuleBlocker.kt:3488](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L3488)부터 [AppRuleBlocker.kt:3597](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L3597)의 실제 `service.windows`와 `rootInActiveWindow` 변환 경로
  - [canonical T23 closure evidence](app-rule-enforcement-baseline.md#ticket-23-closure-evidence--2026-09-10)의 11 frozen visibility/ownership cases, focused `37/37` connected result and target/warning outcomes on iPlay50
- **Mitigation or decision needed:** ticket 29에서 정확히 `Xiaomi Pad Pro 2025 12.7`을 대상으로 Android 15 또는 Android 16의 installed build/channel, 실제 접근성 service, event timestamp, `windows` package 집합과 application window count, active root package, evaluator 결과, 시작된 Guardian activity를 기록한다. 대상 build가 일치하지 않으면 대체 기기로 진행하지 않는다.
- **Acceptance criteria:** `Xiaomi Pad Pro 2025 12.7` Android 15 또는 Android 16에서 다음 세 시나리오를 반복 통과한다. 1) 전체 앱 사용 금지 시간 시작, 2) 다른 규칙 사용량 소진 뒤 30초 이상 보호자 추가시간 만료, 3) split screen에서 두 앱의 독립 경계. 각 시나리오에서 정책표가 정한 시간 안에 denial을 확인하고, 다른 앱 전환 뒤 stale package에 대한 중복 Guardian이 없음을 확인한다. 이 증거 전에는 AR004와 `reported incident closed`를 완료로 기록하지 않으며, 구현 완료를 OEM 해결이나 release readiness로 해석하지 않는다.
- **Target refactor phase:** `Phase 0`에서 시나리오와 계측을 준비하고 `Phase 1`의 production adapter 검증에서 실행한다.

## 플랫폼 및 시간 기반의 잔여 한계

### AR 005 deep sleep에서 wall clock 경계와 uptime 예약의 차이

- **Status / Severity:** `잔여 위험` / `P2`
- **Exact trigger:** 앱 규칙 planner가 다음 wall clock 경계를 계산한 뒤 기기가 doze 또는 deep sleep에 들어가고, 경계가 수면 중 지나간다.
- **Current behavior:** `Handler.postDelayed()`의 지연은 uptime 기준이라 수면 중 callback이 실행되지 않을 수 있다. 현재 구현은 분 단위 ticker와 `SCREEN_ON`, `USER_PRESENT` 경로에서 깨어난 뒤 reconciliation을 게시해 보완한다. 따라서 경계 자체가 수면 중 즉시 처리되는 것은 아니다.
- **Impact:** 수면 중 실제 앱 사용은 없더라도, 기상 직후 접근성 이벤트 또는 USER_PRESENT 경로가 누락되면 제한 적용이 늦어질 수 있다. 직접적인 앱 사용 금지 누락으로 확정된 것은 아니므로 AR 001과 분리해 잔여 위험으로 기록한다.
- **Evidence:**
  - [AppRuleRecheckPlanner.kt:20](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleRecheckPlanner.kt#L20)부터 [AppRuleRecheckPlanner.kt:64](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleRecheckPlanner.kt#L64)의 wall clock 기반 경계 계산
  - [AppRuleBlocker.kt:383](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L383)부터 [AppRuleBlocker.kt:404](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L404)의 ticker와 wake reconciliation
  - [AppRuleBlocker.kt:734](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L734)부터 [AppRuleBlocker.kt:758](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L758)의 screen wake 경로
- **Mitigation or decision needed:** scheduler가 wall clock 경계와 monotonic delay를 명시적으로 변환하고, wake 시 현재 시각을 다시 계산하는 계약을 만든다. Alarm 또는 다른 wake mechanism을 도입할지는 배터리 비용과 실제 지연 측정 뒤 결정한다.
- **Acceptance criteria:** 가상 수면에서 wall clock이 경계를 넘은 뒤 기상하는 테스트가 있고, 기상 후 정책표가 정한 최대 지연 안에 최신 rule decision이 발생한다. 수면 중 앱 사용을 기록하거나 없는 사용을 소급 청구하지 않는다.
- **Target refactor phase:** `Phase 2 asynchronous decision worker와 scheduler contract`.

### AR 006 접근성 기반 보호의 수용된 우회 경로

- **Status / Severity:** `수용된 한계` / `해당 없음`
- **Exact trigger:** 보호자가 Android safe mode로 부팅하거나 Curbox를 force stop하고, OEM 시스템 화면을 사용하거나 물리적인 복구 경로를 이용한다.
- **Current behavior:** Curbox는 Device Owner와 Lock Task를 사용하지 않고 접근성 서비스, overlay와 일반 Device Admin으로 최선형 보호를 제공한다. 위 경로에서 서비스를 끄거나 설정을 바꾸는 일을 키오스크 수준으로 막지 않는다.
- **Impact:** 사용자는 일부 제한을 우회하거나 보호 기능을 제거할 수 있다. 이는 현재 재검사 구현의 결함이 아니라 복구 가능성과 개인 태블릿 배포를 우선한 제품 결정이다.
- **Evidence:**
  - [ADR 0003](../adr/0003-use-best-effort-device-protection.md)
  - [앱 규칙 요구사항: 관리 잠금과 보호 방식](../requirements/app-rules.md#L146)
  - [앱 규칙 스펙: best effort 보호와 제외 범위](../specs/app-rules-github-issue.md#L141)
- **Mitigation or decision needed:** UI와 도움말에서 최선형 보호와 키오스크 보장 부재를 계속 명시한다. 서비스 heartbeat, 재연결과 일반적인 cleanup은 유지하되 Device Owner 등록이나 안전 모드 차단을 이 리팩토링의 해결책으로 추가하지 않는다.
- **Acceptance criteria:** 문서와 사용자 안내가 safe mode, force stop, OEM 시스템 화면과 물리적 복구를 완전 차단한다고 말하지 않는다. 정상 경로에서 접근성 서비스가 다시 연결되면 재연결 reconciliation이 동작하는지만 회귀 테스트한다.
- **Target refactor phase:** `계획 없음` / ADR 0003에 따라 수용.

## 구조 및 성능 부채

### AR 007 접근성 callback의 persistence flush와 decision ordering

- **Status / Severity:** `닫힘 (T24 deterministic ordering; p95 remains T27)` / `P2`
- **Exact trigger:** 접근성 callback이 foreground facts를 worker에 넘긴 직후 evaluator가 visible-session persistence가 끝나기 전에 실행되면, 이전 상태를 기준으로 app rule decision이 만들어질 수 있다.
- **Current behavior:** 접근성 callback은 immutable foreground facts를 만들고 `SerializedDecisionWorker.submit()`의 nonblocking value handoff로 반환한다. worker 하나가 visible-session reconciliation, persistence flush/commit, `AppRuleEnforcement.check()`의 persisted-session read와 evaluator 호출을 같은 serialized path에서 처리한다. evaluator는 필요한 persistence completion 이후에만 실행된다.
- **Impact:** T24의 두 frozen callback-flush cases는 결정적 ordering evidence로 닫혔다. p95 callback/decision 측정과 Xiaomi OEM 검증은 각각 T27과 T29의 별도 scope이며 이 기록으로 확정하지 않는다.
- **Evidence:**
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 callback-to-worker handoff
  - [SerializedDecisionWorker.kt](../../app/src/main/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorker.kt)의 serialized reconciliation, persistence, evaluation path
  - [AppRuleBlockerCallbackFlushOrderingRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerCallbackFlushOrderingRedTest.kt)의 delayed-read와 rapid-switch ordering traces
  - [SerializedDecisionWorkerTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorkerTest.kt)의 focused JVM seam coverage
  - [canonical baseline inventory](app-rule-enforcement-baseline.md#ticket-24-closure-evidence--2026-09-10)의 T24 closure evidence
- **Mitigation or decision needed:** existing serialized worker ownership is retained. The callback remains nonblocking, and the worker waits for the required visible-session persistence completion before evaluation. No latency threshold, blocking callback contract, queue split, or architecture change is selected. Ticket 27 must separately propose and obtain approval for a p95 measurement protocol.
- **Acceptance criteria:** the two stable T24 identifiers pass focused connected verification; the focused JVM worker suite passes; the delayed-read trace orders `callback-return < persistence-read-complete < evaluation`; the rapid-switch trace orders both callback returns before the relevant persistence completions and `evaluation-1`/`evaluation-2` after commits 1/2; Xiaomi validation and p95 measurement remain outside this closure.
- **Target refactor phase:** `Phase 2 asynchronous decision worker`.

### AR 008 shutdown drain 시간과 완료 보장 미측정

- **Status / Severity:** `ticket15와 ticket18 결정적 계약 green, p95 및 운영 숫자 선택 보류` / `P2`
- **Exact trigger:** service가 in flight인 rule evaluation, refresh, notification job 또는 scheduled callback을 가진 채 `onDestroy()`를 호출한다.
- **Current behavior:** `AppRuleBlocker.onDestroy()`는 lifecycle flag, generation과 adapter-private worker-instance token을 먼저 무효화하고, 예약 callback과 scope를 취소하며, worker를 `RecoveryOnlyStop`으로 중단한 뒤 receiver를 해제한다. `onDestroyForMeasurement()`는 production timeout으로 사용하지 않는 candidate-only `DeadlineDrainStop`을 통해 scheduler, handler, worker, scope, receiver와 effect drain을 하나의 absolute deadline에서 측정한다. evaluator, warning, notification의 실제 외부 publication 경계도 같은 destroy fence로 보호한다.
- **Impact:** production은 D6 승인에 따라 아직 numeric drain budget이나 completion guarantee를 선택하지 않았다. 따라서 deadline 측정은 운영 threshold가 아니며, timeout 뒤 unfinished durable session state는 새 연결의 `AppUsageTracker.setup()`이 호출하는 `recoverOpenSessions()`와 generation/use-day filter를 통해서만 복구된다. 측정된 in-memory outcome이나 callback은 새 연결로 재사용하지 않는다.
- **Evidence:**
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 `destroyInternal()`, `DestroyDrainMeasurement`와 refresh/notification/callback counters
  - [SerializedDecisionWorker.kt](../../app/src/main/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorker.kt)의 `TotalDrainDeadline`, `DeadlineDrainStop`과 worker drain snapshot
  - [AppRuleBlockerDestroyFaultRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerDestroyFaultRedTest.kt)의 barrier 기반 ticket15 measurement: final decision, refresh, final notification publication, warning, visible/rearmed handler and alarm publication, usage-reset completion broadcast, worker recheck-plan registration/delivery, event and scheduled-provider provenance-cache invalidation, zero post-destroy effects와 reconnect recovery
  - [SerializedDecisionWorkerTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorkerTest.kt)의 absolute deadline timeout/completion, timeout 전 durable-work snapshot과 same-lifecycle worker-instance replacement tests
  - [AppUsageTracker.kt](../../app/src/main/java/neth/iecal/curbox/trackers/AppUsageTracker.kt)의 `recoverOpenSessions()` reconnect recovery path
  - [ticket 18 implementation evidence](../../.scratch/app-rule-enforcement/issues/18-phase2-fault-cancellation-closure.md#implementation-evidence--replacement-agent-2026-09-07): `AppRuleBlockerDestroyFaultRedTest` `21/21`, Full unit `350/350`, and three debug flavor builds green
- **Mitigation or decision needed:** production에서는 승인된 numeric selection 전까지 `RecoveryOnlyStop`을 유지한다. candidate measurement의 timeout은 worker의 unfinished work를 durable recovery 대상으로 남기고, old generation의 evaluator, warning, notification과 handler publication은 suppress한다. 운영 timeout 숫자와 `DeadlineDrainStop` production 전환은 대표 device latency 측정과 별도 explicit approval 뒤에 결정한다.
- **Acceptance criteria:** the ticket15 deterministic measurement/lifecycle contract is green: injected elapsed deadline measurement includes the final decision, refresh, final notification publication, handler, scheduler, scope and receiver cleanup; post-destroy evaluator, warning, notification, handler, usage-reset broadcast and worker recheck-plan effects are zero; timeout durable work is marked before cancellation and is recovered only through an open durable session's documented `recoverOpenSessions()` path; and repeated same-host/same-lifecycle replacement publishes only the latest generation and worker instance. Ticket18 then closed the eight ticket05/06 fault and cancellation modes with `AppRuleBlockerDestroyFaultRedTest` at `21/21`; this does not choose an operational numeric drain budget. The historical canonical connected baseline retains T24 callback-flush, T21 long-boundary, T22/T23 recheck/visibility, T25 Guardian-lifecycle and T26 debug-fixture REDs as historical inventory. The execution-specific T21 closure observation recorded on 2026-09-10 had only the two T24 and one T26 failures; the later Ticket 22 execution recorded the additional T25 observation, and Ticket 25 subsequently closed that owned lifecycle contract with deterministic focused evidence. No production 2-second or 5-second timeout was selected.
- **Target refactor phase:** the ticket15/ticket18 implementation contracts are recorded complete. Ticket 27 owns callback/decision p95 measurement, and ticket 28 owns the explicit numeric D6 drain-budget decision. Production remains on `RecoveryOnlyStop` until that decision is explicitly made.

### AR 009 AppRuleBlocker의 책임 집중

- **Status / Severity:** `계획된 부채` / `P2`
- **Exact trigger:** foreground evidence 정책, 창 목록 변환, 세션 visible reconciliation, Room evaluator 호출, refresh publication, Handler scheduling, notification과 guardian activity lifecycle 중 하나를 변경한다.
- **Current behavior:** [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)는 현재 약 1,400줄이며 위 책임을 한 클래스에 함께 둔다. 테스트는 여러 private field와 method를 reflection으로 설정하고, Android framework 상태를 provider lambda로 주입한다.
- **Impact:** 한 정책 변경이 scheduler, lifecycle, persistence와 UI 경로에 동시에 영향을 준다. 테스트 seam이 구현 세부에 묶여 있어 AR 001 같은 상태 조합의 회귀를 빠르게 검증하기 어렵고, 리뷰에서 fixed와 open 경계를 놓치기 쉽다.
- **Evidence:**
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 현재 통합 책임
  - [AppRuleBlockerRecheckTest.kt:654](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L654)부터 [AppRuleBlockerRecheckTest.kt:710](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L710)의 fake service와 reflection helper
  - `c17677ae`에서 [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)와 [AppBlockerService.kt](../../app/src/main/java/neth/iecal/curbox/services/AppBlockerService.kt)가 함께 확장된 현재 책임 범위
- **Mitigation or decision needed:** 리팩토링 계획의 순서대로 foreground evidence module을 Phase 1에서,
  asynchronous decision worker를 Phase 2에서 추출한다. keyed `RecheckCoordinator`는 계획의 Phase 3
  진입 조건을 모두 만족할 때만, lifecycle adapter는 Phase 4 trigger가 재현될 때만 추출한다. 각
  module의 입력과 출력은 정책표와 deterministic test seam을 기준으로 정하고, AppRuleBlocker는
  service adapter로 축소한다. module 추출 자체를 AR 001의 정책 결정으로 간주하지 않는다.
- **Acceptance criteria:** evidence와 decision worker는 Android framework 없이 테스트할 수 있고, scheduler와 lifecycle cleanup도 해당 module을 도입한 경우 같은 방식으로 테스트할 수 있다. deletion test는 qualitative design gate로 PR 또는 design record에 기록하고 자동 완료 결과로 사용하지 않는다. 보조 기준으로 public contract가 Android, Room, Handler type을 0개 노출하고, dependency 방향이 `adapter → module`이며, production과 deterministic adapter가 같은 contract test suite를 통과하고, caller에 policy branch가 중복되지 않는지 확인한다. lifecycle host에는 이 기준을 적용할 수 있을 때만 적용한다. coordinator와 lifecycle host는 계획의 조건을 충족할 때만 도입하며, no-go인 경우 그 근거와 defer 결정을 기록한다. service host에는 각 책임의 명시적 interface와 한 개의 lifecycle owner만 남고, reflection으로 private implementation을 조작하는 핵심 테스트가 없어야 한다. 세 flavor build와 기존 외부 동작은 유지한다.
- **Target refactor phase:** `Phase 1`과 `Phase 2`를 기본 경로로 하고, `Phase 3`과 `Phase 4`는 각각의
  조건을 충족할 때만 수행한다.

### AR 010 refresh ordering 위험 재현 후 serialized publication으로 닫힘

- **Status / Severity:** `닫힘` / `P2`
- **Exact trigger:** `INTENT_ACTION_REFRESH_APP_RULES` broadcast가 빠르게 연속 도착하고 동시에 DataStore settings emission이 발생한다. 서로 다른 refresh coroutine이 lock을 기다리는 동안 settings snapshot과 package scope의 관찰 시점이 교차한다.
- **Current behavior:** ticket03의 deterministic evidence는 latest refresh 뒤 stale settings publication이 snapshot, generation과 visible user outcome을 되돌리는 위험을 재현했다. 현재 settings collector와 refresh receiver는 source observation 시점에 하나의 `SourceOrderReservation`으로 `SourceOrderIdentity`와 `RuntimeRevision`을 함께 예약한다. 두 경로는 기존 `refreshMutex` 안의 공통 `applyAndSubmitRuntimePublication`으로 snapshot과 runtime publication을 함께 처리한다. 이미 수용된 revision보다 오래된 emission은 snapshot, generation과 worker publication을 변경하지 않는다. 의미상 no-op인 Settings emission도 accepted revision을 무효화할 수 있으므로 같은 worker publication과 visible reconciliation을 예약한다. worker가 not-ready인 same-lifecycle recovery에서는 readiness 확인, host runtime/revision capture, stop, construction과 installation도 별도의 private handoff critical section으로 직렬화한다.
- **Impact:** ticket03의 결정론적 interleaving에서 latest refresh 뒤 stale publication이 snapshot을 덮어쓰고 visible warning을 표시하는 회귀가 재현됐지만, ticket14 fix 후에는 최신 snapshot과 lifecycle만 visible reconciliation에 도달한다. 별도의 actor나 publication abstraction은 추가하지 않았다.
- **Evidence:**
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 settings collector, refresh receiver, `applySettingsSnapshot`, `refreshPackageScope`와 existing `RuntimePublication` path
  - [ForegroundEvidenceContractTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/ForegroundEvidenceContractTest.kt)의 deterministic barrier. Two concurrent publication observations are held at the same source boundary, then each uses the only publication operation; atomic reservation keeps source `1` paired with revision `1` and source `2` with revision `2`.
  - [SerializedDecisionWorkerTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorkerTest.kt)의 superseding no-op interleaving. A delayed revision `3` visible evaluation is followed by accepted revision `4` with unchanged rules; the stale outcome is filtered and the final accepted revision `4`, lifecycle generation `1`, and allowed visible decision remain authoritative.
  - [AppRuleBlockerRefreshOrderingRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRefreshOrderingRedTest.kt)의 deterministic interleaving through the shared production apply-and-submit path. The test invokes the same helper used by settings and refresh endpoints, captures the posted visible reconciliation, and verifies final snapshot `latest`, latest recheck generation, worker accepted revision, lifecycle generation `1`, visible `AppRulesEvaluation.isAllowed == true`, and no warning activity. The no-op revision is accepted and its replacement reconciliation is observed. The same production-handoff tests also force a worker-not-ready publication N, a delayed revision 1 versus accepted revision 2 after same-generation replacement, and two concurrent replacements where older M captures and pauses while newer N is applied; the production publication boundary observes host revision and snapshot N immediately before N reaches the replacement handoff, proves N cannot complete while M owns that handoff, and then verifies that releasing M leaves N authoritative, so stale M cannot win. `SerializedDecisionWorkerTest` models the production `REFRESH` publication-only facts and waits for both revision-4 outcomes before asserting the visible result.
- **Mitigation or decision:** ticket14 implements the minimum serialized ordering contract. The sequencer exposes exactly two safe caller operations: `nextSourceOrderIdentity()` for non-publication observations and `reserveRuntimePublication()` for settings/refresh publications. The latter reserves source order and runtime revision atomically at source observation. Only a genuinely fresh connection, immediately after its connection-scoped sequencer is initialized or reset, starts host and worker state at the nonnegative `RuntimeRevision(0L)` sentinel. Same-lifecycle worker recovery atomically hands the host's current `RuleRuntime` and `latestRuntimeRevision` to the replacement, so it cannot lower host freshness. Accepted runtime publication remains under the existing `refreshMutex`; worker readiness, runtime capture, stop, construction and installation use a separate private handoff lock, with `runtimeLock` not held while stopping the worker. Stale revisions are ignored at both blocker and worker; every accepted revision that can invalidate a visible result posts a reconciliation, including semantically no-op settings emissions. No actor, Flow, or new public publication abstraction was added.
- **Acceptance criteria:** met. The deterministic tests force the source interleaving, concurrent replacement and production handoff cases, then verify final snapshot, recheck generation, accepted revision, lifecycle generation, visible evaluation, and warning or allow outcome. The focused worker regression verifies that both intended revision-4 outcomes are drained and that a delayed stale publication cannot replace the latest accepted runtime. Stress results were not used as closure evidence.
- **Target refactor phase:** `Phase 2 serialized publication path` completed for this trigger. Ticket15 destroy/reconnect and ticket16 coordinator remain out of scope.

### AR 011 Phase 3 coordinator trigger 결정 — NO-GO

- **Status / Severity:** `닫힘 (NO-GO)` / `P2`
- **Exact trigger:** 동시에 visible이고 서로 다른 independent deadline에 도달한 package들이 한 번의 scheduler wake와 visible reconciliation으로 처리될 때, 한 package의 denial 또는 다음 독립 recheck가 유실되는지 확인한다.
- **Decision:** coordinator는 필요하지 않다. 현재 keyed package plan과 serialized worker tick은 허용 지연 안에 두 deadline을 모두 처리한다. 조건부 coordinator 티켓이나 coordinator production code를 만들지 않는다.
- **Evidence:** [AppRuleBlockerRecheckTest.kt:1155](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L1155)의 deterministic tracer bullet은 fake wall/elapsed clocks와 callback barrier로 A/B의 due 시각을 같은 `+60,000 ms`에 맞춘다. 각 initial plan이 실제 package keyed AlarmManager registration과 recheck를 만든 뒤 두 registration token을 결정적으로 추출하고, 정렬된 package/token intent를 `schedulerWakeReceiver`에 전달한다. 첫 delivery 직후에는 delivered package만 `scheduledAlarms`와 `scheduledRechecks`에서 사라지고 다른 package가 양쪽에 남으며, 두 번째 delivery 직후에는 두 package가 양쪽 keyed map에서 모두 사라진다. 두 scheduler wake는 visible callback 1회로 coalesce됐다. 외부 결과는 A의 denial decision과 service `startActivity` guardian launch intent, B의 `AppRulesEvaluation.isAllowed == true`, warning package가 A 하나뿐인 것, B의 다음 독립 plan이 `base + 120,000 ms`인 것으로 확인했다. Full Debug Android 13 기기 `iPlay50_mini_Pro`에서 focused test가 통과했다.
- **Measurement boundary:** 내부 counter만 세지 않고 keyed scheduler registration과 token delivery, evaluator decision, warning package와 guardian activity intent, 다음 package별 due plan을 관찰했다. stress 또는 timing 우연에 의존하지 않고 고정 clock, 고정 snapshot, latch와 single callback 실행으로 재현한다.
- **Residual risk:** 이 evidence는 현재 AppRuleBlocker/worker contract의 package-keyed behavior를 판정한다. OEM scheduler 변형, lifecycle host 추출과 Phase 4 cleanup contract는 검증하거나 구현하지 않았으며, 해당 변화가 생기면 이 tracer bullet을 다시 실행해야 한다.
- **Acceptance criteria:** 충족. 독립 deadline 유실 또는 잘못된 coalescing을 외부 allow/denial로 재현하지 못했고, Phase 3 coordinator 진입 조건이 성립하지 않는다. Former Phase 4 blocked/inconclusive conclusion is historical and superseded: `AppRuleBlocker` cleanup runs first, `cleanupFeature` isolates every actual feature cleanup, and the canonical prior-feature-skips-AppRule Trigger 1 is absent. No synthetic Trigger 1 fault-ordering experiment was executed.
- **Target refactor phase:** `Phase 3 coordinator decision` 완료. `Phase 4 lifecycle host` closes `DONE/NO-GO; ticket closed; architecture gate closed`; no lifecycle host implementation is warranted.

### AR 012 Phase 4 lifecycle host trigger 결정 — DONE/NO-GO; ticket closed; architecture gate closed

- **Status / Severity:** `DONE/NO-GO; ticket closed; architecture gate closed` / `P2`
- **Exact triggers:** 다음 네 trigger는 서로 대체하거나 하나로 축약하지 않는다.
  1. feature 하나의 cleanup exception이 다른 feature cleanup 또는 AppRuleBlocker의 예약 취소를 다시 건너뛴다.
  2. 두 번째 독립 scheduler feature가 setup, destroy, generation과 receiver 규칙을 복사하고 그 복사가 회귀를 만든다.
  3. destroy/setup race의 결정적 instrumentation이 guard 뒤 callback, worker 또는 notification side effect를 관찰한다.
  4. reconnect ownership이 분산되어 feature가 stale service reference 또는 receiver를 유지한다.
  Class size, reflection fixture, 반복된 registration attempt와 책임 집중만으로는 trigger가 아니다.
- **Measured scope:** ticket17/18의 instrumentation은 `AppRuleBlocker` component와 receiver helper를 검증한다. ticket19 debug tracer는 실제 framework bind/disable/rebind, 같은 PID의 새 service/receiver identity, distinct PID/token, 15개 service-wide receiver identity, ActivityManager filter 수와 UUID-correlated delivery를 측정했다. `0fd5d473`의 수동 attach probe는 qualifying evidence에서 제외한 상태를 유지한다.
- **Evidence:**
  - `AppRuleBlockerDestroyFaultRedTest`의 21개 test는 component boundary에서 cleanup, generation fence와 post-guard external effect containment을 검증하며 green이다. 실제 framework service reconnect 증거는 아니다.
  - [AppRuleReceiverLifecycleTest.kt:8](../../app/src/test/java/neth/iecal/curbox/domain/apprules/AppRuleReceiverLifecycleTest.kt#L8)는 AppRule receiver helper의 registration failure rollback과 idempotent cleanup을 검증하지만 service-wide active ownership을 측정하지 않는다.
  - Canonical Trigger 1의 `prior feature cleanup skips AppRule cleanup/cancellation` 경로는 현재 production ordering에서 발생할 수 없다. production `AppBlockerService.onDestroy()`는 `AppRuleBlocker`를 첫 feature cleanup으로 실행하고, 모든 실제 feature cleanup은 `cleanupFeature`로 독립적으로 containment된다. 이는 production-order 분석이며 synthetic Trigger 1 fault-ordering evidence가 아니다. No synthetic Trigger 1 fault-ordering experiment was executed.
  - Trigger 3은 실제 disable 중 entered refresh barrier를 release한 뒤 resumed production callback completion ACK와 전 work-count zero, 두 번째 quiescence에서 안정된 publication/effect/failure count까지 확인했다. 2026-09-09 최종 실제 기기 run은 quiescent/reset baseline과 Guardian 부재 뒤 UUID one-shot window를 arm했다. 첫 REAL_EVENT/Calculator DecisionRequest의 source identity 22를 소비하고 같은 identity의 denied evaluation, denied DecisionOutcome, warning-before-framework-call과 이후 새 top-resumed `GuardianApprovalActivity`를 순서대로 확인했다. 총 evaluator denial은 3회, allow는 0회, warning framework boundary는 1회였다. Install과 removal은 각각 별도 UUID framework delivery 1회, 그 callback이 예약한 source/revision의 production publication 1회, callback completion ACK와 scoped quiescence를 확인했다. 따라서 untagged publication 증가는 mutation 성공 조건을 충족하지 못한다. Cleanup은 단일 DataStore transaction에서 UUID 항목만 effective 및 matching pending APP_RULES snapshot에서 제거하고 unrelated 항목/metadata를 보존한 뒤 effective/pending/editing 세 view 부재를 확인했다. 장기 예약 callback 3개는 실제 framework disable에서 0으로 drain된 뒤 같은 PID 새 service로 rebound했다.
  - 두 번째 trigger의 독립 scheduler caller는 현재 production tree에 없다. 가상의 caller나 abstraction은 추가하지 않았다.
  - Trigger 4는 같은 PID rebind와 distinct-PID rebind에서 service/receiver identity 교체, 16→0→16 process filter 수, old-PID filter zero와 UUID별 한 delivery를 확인했다. narrow AppRule receiver reapply도 filter 수와 단일 delivery를 유지했다. Shizuku `IntentReceiverLeaked`는 두 destroy에서 재현됐지만 stale filter/delivery가 없어 teardown anomaly로만 분류한다.
  - final disable evidence는 AppRule registration zero, scope inactive, process filter zero와 active service record 부재에 한정한다. library-owned resource까지 complete teardown이라고 주장하지 않는다.
- **Decision:** **DONE/NO-GO; ticket closed; architecture gate closed**. Earlier `INCONCLUSIVE`/open conclusion is superseded by the production-order analysis above: because `AppRuleBlocker` is the first feature cleanup and every actual feature cleanup is independently contained by `cleanupFeature`, canonical Trigger 1 cannot occur in the current production ordering. No synthetic Trigger 1 fault-ordering experiment was executed. No lifecycle host ticket or implementation is warranted.
- **Mitigation or decision needed:** None under the current production ordering. Preserve the existing scoped lifecycle evidence and reassess only if production cleanup ordering or containment changes.
- **Acceptance criteria:** The current production path and the conditional GO-only Trigger 1 condition were evaluated. The GO-only synthetic-fault condition is not applicable and was not tested; no lifecycle host implementation is approved or needed.
- **Target refactor phase:** `Phase 4 lifecycle host decision` completed as `DONE/NO-GO; ticket closed; architecture gate closed`.

## `c17677ae`에서 이미 닫힌 항목

다음은 현재 커밋에서 수정됐으므로 위 미해결 목록에 다시 결함으로 올리지 않는다. 회귀가 발견되면 이 문서에 새 증거와 함께 별도 항목을 추가한다.

- **전체 앱 대상이 stale launcher 목록에서 사라지는 경로:** evaluator가 현재 event package를 all apps fallback으로 전달한다. [AppRuleEvaluator.kt:91](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleEvaluator.kt#L91)부터 [AppRuleEvaluator.kt:106](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleEvaluator.kt#L106)을 참조한다.
- **synthetic Handler callback의 예외와 event recycle 누락:** callback, synthetic event 생성, evaluator 호출과 recycle에 containment이 있다. [AppRuleBlocker.kt:1012](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1012)부터 [AppRuleBlocker.kt:1078](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1078)을 참조한다.
- **split screen에서 하나의 예약이 다른 패키지를 덮어쓰는 경로:** 패키지별 `scheduledRechecks` keyed job을 유지한다. [AppRuleBlocker.kt:933](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L933)부터 [AppRuleBlocker.kt:979](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L979)을 참조한다.
- **서비스 재연결 후 기존 visible application을 검사하지 않는 경로:** setup 완료 후 visible reconciliation을 게시한다. [AppRuleBlocker.kt:219](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L219)부터 [AppRuleBlocker.kt:224](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L224)을 참조한다.
- **AppBlockerService의 한 feature cleanup 실패가 AppRuleBlocker cleanup을 건너뛰는 경로:** feature별 containment로 분리됐다. [AppBlockerService.kt:259](../../app/src/main/java/neth/iecal/curbox/services/AppBlockerService.kt#L259)부터 [AppBlockerService.kt:303](../../app/src/main/java/neth/iecal/curbox/services/AppBlockerService.kt#L303)을 참조한다.

이 목록의 AR 001 deterministic contract와 AR 003 deterministic harness 범위는 T21에서
해결됐고, T22의 recheck와 T23의 deterministic visibility ownership contract도 각각의
closure evidence로 해결됐다. 이는 실제 OEM 동작이나 보고된 incident가 종료됐다는 뜻은
아니다. AR 002의 13행 정책표와 R5 A 선택은 승인됐고, 실제 Xiaomi 검증은 ticket 29와 AR004에
남아 있다.
