# 앱 규칙 재검사의 알려진 한계와 미해결 항목

기준 커밋은 `c17677ae` (`fix: enforce app rules across time boundaries`)이다. 이 문서는 해당 커밋 이후에도 남아 있는 결함, 결정 대기 사항, 테스트 공백, 수용된 플랫폼 한계와 구조 및 성능 부채의 단일 정본이다. 실행 순서와 변경 계획은 [앱 규칙 재검사 리팩토링 계획](../plans/app-rule-enforcement-refactor.md)을 따른다.

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
동작을 해결했다는 뜻이 아니다. 대상 샤오신 패드 프로 12.7 Android 16의 실제 증거가 없는
현재 상태에서는 다음 두 상태를 닫지 않는다.

| 상태 | 현재 상태 | 완료 조건 |
| --- | --- | --- |
| `refactor implementation complete` | `별도 판정` | 코드와 자동화 검증의 completion criteria로만 판정한다. 기기 검증이나 incident 종료를 포함하지 않는다. |
| `AR004 Android16 device verification complete` | `열림` | 대상 기기에서 정책표의 시나리오와 계측 증거를 남긴다. |
| `reported incident closed` | `열림` | AR004 증거와 보고된 재현 조건의 해결 확인을 모두 남긴다. |

AR004 또는 incident가 열린 동안에는 OEM 해결이나 release readiness를 주장하지 않는다. 구현
완료와 현장 검증 및 incident 종료의 상태 변경은 각각 별도로 기록한다.

## 확정 미해결 결함

### AR 001 장시간 보호자 추가시간 뒤 가시성 증거 만료

- **Status / Severity:** `확정 미해결` / `P1`
- **Exact trigger:** 앱 규칙의 직접 사용 가능 시간이 소진된 상태에서 보호자 추가시간이 5초보다 길게 남아 있고, 앱이 계속 보이는 동안 새로운 `TYPE_WINDOW_STATE_CHANGED` 이벤트가 오지 않는다. 동시에 `service.windows`가 앱을 누락한 오래된 목록을 반환하거나 빈 목록 또는 `null` root를 반환하고, 다른 신뢰할 수 있는 비필수 앱 root도 없다.
- **Current behavior:** 재검사 시점에는 `currentForegroundEvidenceAtElapsedMs`가 `FOREGROUND_EVIDENCE_MAX_AGE_MS = 5_000`을 지나 있다. `packageVisibility()`는 대상 앱이 창 목록이나 신뢰할 수 있는 active root에서 확인되지 않으면 `UNKNOWN`을 반환한다. 세 번의 짧은 재시도 뒤 `canUseForegroundFallback()`도 만료된 이벤트 증거를 거부하므로, 규칙 평가 없이 20초 회복 재검사만 반복한다. synthetic event는 foreground 증거의 시각을 갱신하지 않는다.
- **Impact:** 시간 구간의 전면 앱 사용 금지 규칙이나 추가시간 만료 뒤의 다른 규칙이 앱이 계속 보이는 동안 잠기지 않을 수 있다. 새 접근성 이벤트나 신뢰할 수 있는 창 정보가 나올 때까지 제한이 늦어지는 것이 아니라 사실상 무기한 늦어질 수 있다. 이는 샤오신 패드 프로 12.7 Android 16에서 보고된 증상과 같은 종류의 실패 경로다.
- **Evidence:**
  - [AppRuleBlocker.kt:61](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L61)부터 [AppRuleBlocker.kt:64](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L64)의 재시도, 20초 회복, 5초 증거 만료 상수
  - [AppRuleBlocker.kt:983](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L983)부터 [AppRuleBlocker.kt:1008](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1008)의 `UNKNOWN` 처리
  - [AppRuleBlocker.kt:1281](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1281)부터 [AppRuleBlocker.kt:1339](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1339)의 증거 만료와 fallback 판정
  - [AppRuleRecheckPlanner.kt:20](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleRecheckPlanner.kt#L20)부터 [AppRuleRecheckPlanner.kt:64](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleRecheckPlanner.kt#L64)의 다음 경계 예약
- **Mitigation or decision needed:** 승인된 [foreground evidence policy matrix](../plans/app-rule-enforcement-refactor.md#foreground-evidence-policy-matrix--approved-2026-08-31)를 따른다. R5는 A안으로 확정되어, 1.5초 bounded retry 뒤 마지막 비필수 package의 restriction을 fail closed한다. 5초 TTL을 늘리는 것만으로 해결하지 않으며, 다른 앱 root가 확실히 활성인 경우에는 이전 앱을 잠그지 않는다. 실제 adapter와 evaluator 변경은 Phase 1에서 수행한다.
- **Acceptance criteria:** 가상 시각과 창 제공자를 주입한 테스트에서 30초 이상의 추가시간, 이벤트 없음, 오래된 목록, 빈 목록, `null` root를 재현한다. 정책이 평가를 요구하는 각 상태에서는 제한 시간 안에 실제 evaluator 결과와 대상 규칙의 거부 또는 허용을 관찰하고, 정책이 보류를 요구하는 상태에서는 그 보류 결과와 최대 지연을 관찰한다. 동시에 다른 비필수 앱 root가 확실한 경우에는 이전 앱을 재평가하지 않는다. 샤오신 패드 프로 12.7 Android 16에서 같은 시나리오를 실제 `windows`와 `rootInActiveWindow`로 확인한다.
- **Target refactor phase:** 리팩토링 계획의 `Phase 0 정책 결정`에서 결정하고 `Phase 1 foreground evidence 모듈`에서 수정한다.

## 결정 기록과 구현 대기 사항

### AR 002 foreground evidence 정책표 승인 완료, 구현 대기

- **Status / Severity:** `결정됨, 구현 대기` / `P1`
- **Exact trigger:** 정상 접근성 이벤트, synthetic 재검사, 서비스 재연결, 화면 켜짐과 분할 화면에서 이벤트의 최신성, `service.windows`의 상태, `rootInActiveWindow`의 상태가 서로 다르게 관찰된다. 특히 이벤트가 만료된 뒤 창 목록이 대상 앱을 누락하거나 창 package가 비어 있는 조합에서 정책이 필요하다.
- **Current behavior:** `checkCurrentlyVisibleApplications()`, `packageVisibility()`, `canUseForegroundFallback()`, `readApplicationWindowSnapshot()`과 `readActiveWindowSnapshot()`이 각각 일부 조합을 판단한다. 실행 계약은 [리팩토링 계획의 승인된 13행 정책표](../plans/app-rule-enforcement-refactor.md#foreground-evidence-policy-matrix--approved-2026-08-31)에 고정됐지만, production adapter와 module 구현은 아직 그 계약으로 이행되지 않았다.
- **Impact:** 같은 플랫폼 상태가 호출 경로에 따라 다르게 처리될 수 있고, AR 001을 고칠 때 미차단을 줄이려다 오차단을 만들 수 있다. 테스트가 어떤 결과를 보장해야 하는지도 명확하지 않다.
- **Evidence:**
  - [AppRuleBlocker.kt:591](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L591)부터 [AppRuleBlocker.kt:732](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L732)의 visible package reconciliation
  - [AppRuleBlocker.kt:1081](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1081)부터 [AppRuleBlocker.kt:1115](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1115)의 `packageVisibility()`
  - [AppRuleBlocker.kt:1287](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1287)부터 [AppRuleBlocker.kt:1339](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1339)의 foreground fallback
  - [앱 규칙 요구사항: 전면 사용량 추적](../requirements/app-rules.md#L91)와 [앱 규칙 스펙: window reconciliation 결정](../specs/app-rules-github-issue.md#L93)
- **Mitigation or decision needed:** 승인된 표의 13개 행과 `AR002-R01`부터 `AR002-R13`까지의
  mapping을 Phase 0 deterministic test와 Phase 1 foreground evidence module의 동일 계약으로
  구현한다. R5에는 승인된 A안을 사용하고 B는 오차단과 제한 누락의 tradeoff를 기록한 비활성
  대안으로만 남긴다. R6는 이전 후보가 없는 최초 관찰에서도 확실한 비필수 active root를
  첫 후보로 평가하며, 이전 후보가 있을 때만 stale 이전 package와의 전환을 판정한다.
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

- **Status / Severity:** `테스트 공백` / `P1`
- **Exact trigger:** 보호자 추가시간이 foreground evidence TTL보다 길고, allowance 경계에서 이벤트가 오지 않으며, 창 제공자가 오래된 목록, 빈 목록 또는 `null` root를 반환하는 경우다.
- **Current behavior:** 현재 Android instrumentation 테스트는 `System.currentTimeMillis()`와 `SystemClock.sleep()`에 의존하고 Handler의 실제 uptime을 기다린다. 3초 추가시간을 확인하는 테스트는 callback이 예약되고 창 읽기가 일어났는지만 확인한다. 2초 추가시간 테스트는 실제 denial을 확인하지만 TTL보다 긴 추가시간과 모든 불완전한 창 상태를 조합하지 않는다.
- **Impact:** 테스트가 통과해도 AR 001처럼 callback은 실행됐지만 evaluator가 실행되지 않는 경로를 놓칠 수 있다. 실제 시간과 기기 부하에 따라 테스트가 불안정해지고, 실패 원인도 분리하기 어렵다.
- **Evidence:**
  - [AppRuleBlockerRecheckTest.kt:80](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L80)부터 [AppRuleBlockerRecheckTest.kt:114](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L114)의 3초 grant와 `windowsReads` 단독 확인
  - [AppRuleBlockerRecheckTest.kt:146](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L146)부터 [AppRuleBlockerRecheckTest.kt:223](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L223)의 2초 grant와 5초 sleep
  - [AppRuleBlocker.kt:1281](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1281)부터 [AppRuleBlocker.kt:1285](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1285)의 elapsed time TTL
- **Mitigation or decision needed:** 시계 세 종류인 wall clock, elapsed clock, Handler scheduler를 주입할 수 있는 deterministic seam을 만든다. 제품 동작 테스트는 evaluator 호출, 최종 denial과 허용 결과를 외부에서 확인하고, concurrency/performance contract 테스트는 worker thread, queue idle, post-destroy side effect와 latency budget을 확인한다. visibility 상태를 순서대로 공급하는 테스트를 JVM 또는 빠른 instrumentation으로 작성한다.
- **Acceptance criteria:** sleep 없이 30초 이상의 grant를 가상 시간으로 진행하는 테스트가 있다. `no event + stale window`, `no event + empty window`, `no event + null root`를 각각 실행하고, callback 실행 여부가 아니라 evaluator 결과와 `startedActivities`의 denial payload를 확인한다. 다른 active root가 있는 경우의 비잠금 결과도 같은 제품 동작 테스트 묶음에 포함한다. 별도의 contract 테스트는 thread/queue idle, post-destroy side effect와 latency budget을 관찰할 수 있지만 private call order를 검증하지 않는다.
- **Target refactor phase:** `Phase 0 deterministic harness`, `Phase 1 foreground evidence 모듈`, 이후 scheduler 관련 검증은 `Phase 2`에서 수행한다.

### AR 004 Android 16 실제 창 동작 테스트 부재

- **Status / Severity:** `테스트 공백` / `P1`
- **Exact trigger:** 샤오신 패드 프로 12.7 Android 16에서 앱을 계속 전면에 둔 채 보호자 추가시간 경계와 전체 앱 사용 금지 시간 경계를 통과한다. 새 window event가 없거나 OEM이 오래된 창 목록, 빈 목록, `null` root를 반환하는 상태를 포함한다.
- **Current behavior:** 현재 테스트는 `RecordingService`와 `applicationWindowSnapshotProvider`, `activeWindowSnapshotProvider` fake를 사용한다. split screen과 재연결 시나리오도 실제 `AccessibilityService.windows` 또는 `rootInActiveWindow`를 읽지 않는다.
- **Impact:** Android 16 또는 특정 OEM의 window provider 동작과 현재 fallback 정책이 맞는지 증명하지 못한다. SM F721N 등 다른 기기에서 통과한 결과를 샤오신 패드의 보고된 실패가 해결됐다는 증거로 사용할 수 없다.
- **Evidence:**
  - [AppRuleBlockerRecheckTest.kt:435](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L435)부터 [AppRuleBlockerRecheckTest.kt:467](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L467)의 fake reconnect test
  - [AppRuleBlockerRecheckTest.kt:470](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L470)부터 [AppRuleBlockerRecheckTest.kt:512](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt#L512)의 fake split screen test
  - [AppRuleBlocker.kt:1118](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1118)부터 [AppRuleBlocker.kt:1151](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1151)의 실제 `service.windows` 변환 경로
- **Mitigation or decision needed:** 테스트 기기에서 실제 접근성 서비스를 활성화하고, 이벤트 timestamp, `windows`의 package 집합과 application window count, active root package, evaluator 결과, 시작된 guardian activity를 기록한다. 대상 기기를 사용할 수 없으면 이 항목을 미검증으로 유지한다.
- **Acceptance criteria:** 샤오신 패드 프로 12.7 Android 16에서 다음 세 시나리오를 반복 통과한다. 1) 전체 앱 사용 금지 시간 시작, 2) 다른 규칙 사용량 소진 뒤 30초 이상 보호자 추가시간 만료, 3) split screen에서 두 앱의 독립 경계. 각 시나리오에서 정책표가 정한 시간 안에 denial을 확인하고, 다른 앱 전환 뒤 stale package에 대한 중복 guardian이 없음을 확인한다. 이 증거 전에는 AR004와 `reported incident closed`를 완료로 기록하지 않으며, 구현 완료를 OEM 해결이나 release readiness로 해석하지 않는다.
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

### AR 007 접근성 callback의 main thread `runBlocking` DB 대기

- **Status / Severity:** `계획된 부채` / `P2`
- **Exact trigger:** `AppBlockerService.onAccessibilityEvent()`가 `AppRuleBlocker.doAppRuleCheck()`를 호출하고, 앱 규칙이 활성인 상태에서 Room 세션 조회가 필요하다. setup 시에도 초기 settings를 동기적으로 읽는다.
- **Current behavior:** 접근성 서비스의 main callback에서 `runBlocking(Dispatchers.IO)`로 Room 조회가 끝날 때까지 호출자를 막는다. `Dispatchers.IO`는 DB 작업 위치만 바꾸며 callback을 비동기로 만들지 않는다. `AppRuleEnforcement.check`는 current use day 세션 전체를 읽는다.
- **Impact:** 세션 원장이 커지거나 Room이 잠시 느려지면 접근성 이벤트 처리와 다음 window event가 지연될 수 있다. 직접적인 기능 실패로 확정하지는 않았지만, AR 001의 경계 처리와 서비스 반응성을 같은 main thread 지연에 의존하게 만든다.
- **Evidence:**
  - [AppBlockerService.kt:104](../../app/src/main/java/neth/iecal/curbox/services/AppBlockerService.kt#L104)부터 [AppBlockerService.kt:122](../../app/src/main/java/neth/iecal/curbox/services/AppBlockerService.kt#L122)의 동기 app rule callback 호출
  - [AppRuleBlocker.kt:328](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L328)부터 [AppRuleBlocker.kt:344](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L344)의 main callback 내부 `runBlocking`
  - [AppUsageTracker.kt:247](../../app/src/main/java/neth/iecal/curbox/trackers/AppUsageTracker.kt#L247)부터 [AppUsageTracker.kt:258](../../app/src/main/java/neth/iecal/curbox/trackers/AppUsageTracker.kt#L258)의 `onEvent()` visible package reconciliation 진입점
  - [AppUsageTracker.kt:436](../../app/src/main/java/neth/iecal/curbox/trackers/AppUsageTracker.kt#L436)부터 [AppUsageTracker.kt:496](../../app/src/main/java/neth/iecal/curbox/trackers/AppUsageTracker.kt#L496)의 visible-session reconciliation과 Room boundary writer
  - [AppRuleEnforcement.kt:24](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleEnforcement.kt#L24)부터 [AppRuleEnforcement.kt:46](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleEnforcement.kt#L46)의 Room 세션 조회
- **Mitigation or decision needed:** 현재 `AppUsageTracker.onEvent()`가 visible-session reconciliation과
  그 Room writer의 관찰 진입점을 소유한다. Phase 2에서 `onEvent()`는 immutable visible-set
  observation만 하나의 serialized handoff로 넘기고, handoff 이후의 reconciliation writer와
  evaluator decision worker는 단일 직렬화된 실행 경로의 한 owner가 맡는다. 이전 session flush와
  Room commit 완료를 확인한 뒤에만 rule decision을 실행한다. tracker queue와 decision queue를
  별도로 두어 순서를 나누지 않는다. callback은 입력을 복사해 worker에 전달하고, 결과의
  generation과 lifecycle을 확인한 뒤 warning을 main thread에서 표시한다. timeout, cancellation,
  storage failure의 fail policy도 함께 정의한다.
- **Acceptance criteria:** main callback이 Room 결과를 기다리지 않고 반환한다. 지연된 fake repository와 빠른 연속 window event에서 `AppUsageTracker.onEvent()`의 visible-session flush와 Room commit이 새 rule decision보다 먼저 완료되며, 각 event의 최신 generation만 warning을 표시한다. tracker와 decision을 별도 queue로 분리하지 않고 하나의 serialized path에서 순서를 보장한다. 대표적인 세션 크기에서 p95 callback blocking time과 decision latency를 측정하고 기준을 계획 문서에 기록한다.
- **Target refactor phase:** `Phase 2 asynchronous decision worker`.

### AR 008 shutdown drain 시간과 완료 보장 미측정

- **Status / Severity:** `ticket15 결정적 기준 green, 인접 fault/cancellation RED 잔존, 운영 숫자 선택 보류` / `P2`
- **Exact trigger:** service가 in flight인 rule evaluation, refresh, notification job 또는 scheduled callback을 가진 채 `onDestroy()`를 호출한다.
- **Current behavior:** `AppRuleBlocker.onDestroy()`는 lifecycle flag, generation과 adapter-private worker-instance token을 먼저 무효화하고, 예약 callback과 scope를 취소하며, worker를 `RecoveryOnlyStop`으로 중단한 뒤 receiver를 해제한다. `onDestroyForMeasurement()`는 production timeout으로 사용하지 않는 candidate-only `DeadlineDrainStop`을 통해 scheduler, handler, worker, scope, receiver와 effect drain을 하나의 absolute deadline에서 측정한다. evaluator, warning, notification의 실제 외부 publication 경계도 같은 destroy fence로 보호한다.
- **Impact:** production은 D6 승인에 따라 아직 numeric drain budget이나 completion guarantee를 선택하지 않았다. 따라서 deadline 측정은 운영 threshold가 아니며, timeout 뒤 unfinished durable session state는 새 연결의 `AppUsageTracker.setup()`이 호출하는 `recoverOpenSessions()`와 generation/use-day filter를 통해서만 복구된다. 측정된 in-memory outcome이나 callback은 새 연결로 재사용하지 않는다.
- **Evidence:**
  - [AppRuleBlocker.kt](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt)의 `destroyInternal()`, `DestroyDrainMeasurement`와 refresh/notification/callback counters
  - [SerializedDecisionWorker.kt](../../app/src/main/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorker.kt)의 `TotalDrainDeadline`, `DeadlineDrainStop`과 worker drain snapshot
  - [AppRuleBlockerDestroyFaultRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerDestroyFaultRedTest.kt)의 barrier 기반 ticket15 measurement: final decision, refresh, final notification publication, warning, visible/rearmed handler and alarm publication, usage-reset completion broadcast, worker recheck-plan registration/delivery, event and scheduled-provider provenance-cache invalidation, zero post-destroy effects와 reconnect recovery
  - [SerializedDecisionWorkerTest.kt](../../app/src/test/java/neth/iecal/curbox/domain/apprules/SerializedDecisionWorkerTest.kt)의 absolute deadline timeout/completion, timeout 전 durable-work snapshot과 same-lifecycle worker-instance replacement tests
  - [AppUsageTracker.kt](../../app/src/main/java/neth/iecal/curbox/trackers/AppUsageTracker.kt)의 `recoverOpenSessions()` reconnect recovery path
- **Mitigation or decision needed:** production에서는 승인된 numeric selection 전까지 `RecoveryOnlyStop`을 유지한다. candidate measurement의 timeout은 worker의 unfinished work를 durable recovery 대상으로 남기고, old generation의 evaluator, warning, notification과 handler publication은 suppress한다. 운영 timeout 숫자와 `DeadlineDrainStop` production 전환은 대표 device latency 측정과 별도 explicit approval 뒤에 결정한다.
- **Acceptance criteria:** the ticket15 deterministic measurement/lifecycle contract is green: injected elapsed deadline measurement includes the final decision, refresh, final notification publication, handler, scheduler, scope and receiver cleanup; post-destroy evaluator, warning, notification, handler, usage-reset broadcast and worker recheck-plan effects are zero; timeout durable work is marked before cancellation and is recovered only through an open durable session's documented `recoverOpenSessions()` path; and repeated same-host/same-lifecycle replacement publishes only the latest generation and worker instance. This does not complete Phase 2. `AppRuleBlockerDestroyFaultRedTest` remains 16/20: its four failing JUnit cases cover eight fault modes. The aggregate ordinary-fault case covers persistence, evaluator, scheduler, callback-post and notification-worker continuation; the other three cases cover evaluator `CancellationException` propagation, worker cancellation continuation and handler cancellation continuation. These are the still-open ticket05/06 fault and cancellation contracts, and no exception to the Phase 2 completion criteria has been approved. The broader connected suite also retains unresolved callback-flush, long-boundary, recheck, Guardian-lifecycle and debug-fixture REDs. No production 2-second or 5-second timeout was selected.
- **Target refactor phase:** `Phase 2` remains open until all eight ticket05/06 fault and cancellation modes are green; ticket15's narrower deterministic lifecycle measurement contract is green. Numeric D6 selection and a fresh conditional `Phase 4` decision remain separate work.

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
- **Acceptance criteria:** 충족. 독립 deadline 유실 또는 잘못된 coalescing을 외부 allow/denial로 재현하지 못했고, Phase 3 coordinator 진입 조건이 성립하지 않는다. Phase 4 판정은 ticket16에 blocked로 새로 기록하며 구현은 보류한다.
- **Target refactor phase:** `Phase 3 coordinator decision` 완료. `Phase 4 lifecycle host`는 별도 evidence와 승인 없이는 시작하지 않는다.

### AR 012 Phase 4 lifecycle host trigger 결정 — evidence insufficient, open

- **Status / Severity:** `review-open, provisional and inconclusive` / `P2`
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
  - Trigger 1은 실제 framework destroy에서 여전히 미검증이다. `AppBlockerService.onDestroy()`의 첫 feature cleanup이 AppRule이라 debug-only 코드가 그 앞에 cleanup fault를 삽입할 production hook이 없다. production seam, subclass 또는 direct destroy 없이 요구한 ordering을 만들 수 없다.
  - Trigger 3은 실제 disable 중 entered refresh barrier를 release한 뒤 resumed production callback completion ACK와 전 work-count zero, 두 번째 quiescence에서 안정된 publication/effect/failure count까지 확인했다. 2026-09-09 최종 실제 기기 run은 quiescent/reset baseline과 Guardian 부재 뒤 UUID one-shot window를 arm했다. 첫 REAL_EVENT/Calculator DecisionRequest의 source identity 22를 소비하고 같은 identity의 denied evaluation, denied DecisionOutcome, warning-before-framework-call과 이후 새 top-resumed `GuardianApprovalActivity`를 순서대로 확인했다. 총 evaluator denial은 3회, allow는 0회, warning framework boundary는 1회였다. Install과 removal은 각각 별도 UUID framework delivery 1회, 그 callback이 예약한 source/revision의 production publication 1회, callback completion ACK와 scoped quiescence를 확인했다. 따라서 untagged publication 증가는 mutation 성공 조건을 충족하지 못한다. Cleanup은 단일 DataStore transaction에서 UUID 항목만 effective 및 matching pending APP_RULES snapshot에서 제거하고 unrelated 항목/metadata를 보존한 뒤 effective/pending/editing 세 view 부재를 확인했다. 장기 예약 callback 3개는 실제 framework disable에서 0으로 drain된 뒤 같은 PID 새 service로 rebound했다.
  - 두 번째 trigger의 독립 scheduler caller는 현재 production tree에 없다. 가상의 caller나 abstraction은 추가하지 않았다.
  - Trigger 4는 같은 PID rebind와 distinct-PID rebind에서 service/receiver identity 교체, 16→0→16 process filter 수, old-PID filter zero와 UUID별 한 delivery를 확인했다. narrow AppRule receiver reapply도 filter 수와 단일 delivery를 유지했다. Shizuku `IntentReceiverLeaked`는 두 destroy에서 재현됐지만 stale filter/delivery가 없어 teardown anomaly로만 분류한다.
  - final disable evidence는 AppRule registration zero, scope inactive, process filter zero와 active service record 부재에 한정한다. library-owned resource까지 complete teardown이라고 주장하지 않는다.
- **Decision:** **INCONCLUSIVE.** 실제 framework lifecycle과 real external denial evidence는 추가됐지만 Trigger 1 fault ordering이 미충족이다. ticket19은 open이고 architecture approval gate는 닫혀 있다. ticket-level NO-GO 또는 no-canonical-trigger 결론을 내리지 않는다.
- **Mitigation or decision needed:** lifecycle host를 구현하거나 implementation ticket을 만들지 않는다. [ticket19 evidence](app-rule-enforcement-ticket19-evidence.md)의 남은 Trigger 1 prerequisite를 production/release surface 없이 충족할 수 있을 때 재평가한다. canonical failure가 재현되면 먼저 per-feature 또는 Shizuku/integration의 더 좁은 remedy와 비교한다.
- **Acceptance criteria:** 실제 framework external denial을 포함한 scoped lifecycle 항목은 충족했지만 Trigger 1 ordering이 남았으므로 tracker와 gate를 완료 처리하지 않는다.
- **Target refactor phase:** `Phase 4 lifecycle host decision`은 evidence prerequisite가 충족될 때까지 open으로 유지한다.

## `c17677ae`에서 이미 닫힌 항목

다음은 현재 커밋에서 수정됐으므로 위 미해결 목록에 다시 결함으로 올리지 않는다. 회귀가 발견되면 이 문서에 새 증거와 함께 별도 항목을 추가한다.

- **전체 앱 대상이 stale launcher 목록에서 사라지는 경로:** evaluator가 현재 event package를 all apps fallback으로 전달한다. [AppRuleEvaluator.kt:91](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleEvaluator.kt#L91)부터 [AppRuleEvaluator.kt:106](../../app/src/main/java/neth/iecal/curbox/domain/apprules/AppRuleEvaluator.kt#L106)을 참조한다.
- **synthetic Handler callback의 예외와 event recycle 누락:** callback, synthetic event 생성, evaluator 호출과 recycle에 containment이 있다. [AppRuleBlocker.kt:1012](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1012)부터 [AppRuleBlocker.kt:1078](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L1078)을 참조한다.
- **split screen에서 하나의 예약이 다른 패키지를 덮어쓰는 경로:** 패키지별 `scheduledRechecks` keyed job을 유지한다. [AppRuleBlocker.kt:933](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L933)부터 [AppRuleBlocker.kt:979](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L979)을 참조한다.
- **서비스 재연결 후 기존 visible application을 검사하지 않는 경로:** setup 완료 후 visible reconciliation을 게시한다. [AppRuleBlocker.kt:219](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L219)부터 [AppRuleBlocker.kt:224](../../app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt#L224)을 참조한다.
- **AppBlockerService의 한 feature cleanup 실패가 AppRuleBlocker cleanup을 건너뛰는 경로:** feature별 containment로 분리됐다. [AppBlockerService.kt:259](../../app/src/main/java/neth/iecal/curbox/services/AppBlockerService.kt#L259)부터 [AppBlockerService.kt:303](../../app/src/main/java/neth/iecal/curbox/services/AppBlockerService.kt#L303)을 참조한다.

이 목록은 AR 001의 정책과 테스트 공백을 해결했다는 뜻이 아니다. AR 002의 13행 정책표와
R5 A 선택은 승인됐지만, 그 계약을 production adapter와 foreground evidence module에
반영하고 AR 003 및 AR 004의 검증을 수행하는 일은 남아 있다.
