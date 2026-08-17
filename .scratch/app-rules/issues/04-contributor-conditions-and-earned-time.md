# 04 — 사용 조건과 적립 허용량 완성

**What to build:** 보호자가 하나 이상의 기여 앱 그룹을 골라 사용 조건과 1대1 적립 허용량을 서로 독립적으로 설정하고, 실제 기여 앱 사용 직후 대상 앱에서 최신 허용량을 쓰게 한다. 같은 앱이 여러 기여 그룹에 있어도 한 규칙 안에서는 한 번만 계산하며, 같은 기여 그룹을 참조하는 여러 규칙은 독립적으로 혜택을 받는다.

**Blocked by:** 03 — 복합 적용 범위와 시간 구간 완성.

**Status:** review-pending

- [x] 규칙은 하나 이상의 기존 앱 그룹을 기여 앱 그룹으로 참조할 수 있다.
- [x] 기여 사용량은 모든 참조 그룹의 현재 앱을 합집합으로 만든 뒤 앱별 실제 사용량을 한 번씩 합산한다.
- [x] 기여 사용량은 대상 규칙의 활성 구간과 무관하게 현재 사용일 전체에서 계산된다.
- [x] 사용 조건과 적립 허용량은 각각 독립적으로 켜고 끌 수 있다.
- [x] 두 옵션이 모두 꺼지면 직접 입력한 사용 가능 시간만 제공된다.
- [x] 사용 조건만 켜면 합산 기여 사용량이 n분에 도달하기 전에는 정상 허용량이 0이고, 도달한 뒤 직접 입력한 사용 가능 시간이 제공된다.
- [x] 적립만 켜면 직접 입력한 사용 가능 시간에 합산 기여 사용량 전체가 1대1로 더해진다.
- [x] 두 옵션을 모두 켜면 n분 전에는 정상 허용량이 0이고, 도달한 뒤 직접 입력한 사용 가능 시간과 기여 사용량 전체가 제공된다.
- [x] 적립 허용량에는 별도의 일일 상한이 없다.
- [x] 하나의 기여 앱 그룹을 참조하는 여러 규칙은 같은 실제 사용분을 각각 독립적으로 계산한다.
- [x] 대상과 기여 앱 중복, 규칙 자신 참조와 순환 참조를 저장 단계에서 금지하지 않는다.
- [x] 모든 계산은 다른 규칙의 허용량이 아니라 앱별 실제 전면 세션만 사용하므로 참조 그래프를 재귀 평가하지 않는다.
- [x] 참조한 기여 앱 그룹이 삭제되거나 해석되지 않으면 규칙은 정상 허용량을 제공하지 않는 방향으로 실패한다.
- [x] 기여 그룹 삭제는 허용하되 사라진 ID 참조를 규칙에 유지하고 알려진 대상 범위를 거부하며 UI에서 깨진 참조와 수정 필요 상태를 표시한다.
- [x] 앱 전환 시 기여 앱 세션이 저장된 뒤 대상 앱 판정이 실행되어 최대 한 번의 이벤트 안에 최신 적립량이 반영된다.
- [x] 설정 화면과 차단 화면은 조건 진행량, 적립량, 직접 허용량과 최종 잔여량을 구분해 보여 준다.
- [x] 기여 그룹 추가와 임계값 감소 및 적립 활성화처럼 제한을 약화할 수 있는 변경은 설정 변경 지연에서 보수적으로 처리된다.
- [x] 네 가지 옵션 조합, 그룹 중복, 공유 참조, 대상 중복, 순환과 깨진 참조가 상위 규칙 판정 테스트로 검증된다.
- [ ] 보호자가 기여 앱을 사용한 직후 대상 앱을 열고 적립분을 소비하는 전체 시나리오가 검증된다. (기기 연결 후 절차 실행 필요)
- [x] 설정 및 세션 갱신의 다중 프로세스 전달과 기여 계산 실패의 서비스 오류 격리가 이 수직 기능에서 검증된다.
- [x] full, playstore, fdroid 변형이 모두 컴파일된다.

## Agent evidence

- `AppRule` stores defaulted contributor group IDs, the independent usage condition and the
  independent one to one earning switch. `AppRuleSnapshot` keeps missing contributor IDs after
  deletion, reports repairable references, and leaves target deletion safeguards unchanged.
- `AppRuleEvaluator` resolves the union of referenced group packages once per rule, merges raw
  foreground intervals per package, clamps them to the current use day rather than the target
  window, and evaluates every rule independently. Direct allowance, condition progress, earned
  allowance and final remaining allowance are separate values. Missing contributors fail closed
  only for the affected target rule, and target or contributor overlap never triggers recursion.
- `AppUsageTracker.onEvent` remains immediately before `AppRuleBlocker.doAppRuleCheck` in
  `AppBlockerService`; the existing reconciler finishes persisted sessions before starting the
  next visible package. Both calls and the delayed warning callback have independent nonfatal
  containment, while cancellation remains propagated in coroutine workers.
- `CreateAppRuleFragment` and `AppRuleGroupsFragment` expose contributor groups, condition and
  earning switches, direct allowance and missing-reference repair state. `WarningActivity` shows
  a runtime breakdown of condition progress, required minutes, earned minutes, direct minutes and
  final remaining minutes.
- The review fix preserves stale contributor IDs while making newly missing references an
  immediately stricter comparison, adds an explicit missing-reference removal row, clears editor
  checkbox maps with the view, consolidates evaluator interval merging, and removes computed
  compatibility aliases without changing persisted Gson fields. Current-use-day card values are
  evaluated from raw foreground sessions and displayed as separate condition, earned, direct and
  final remaining values.
- JBR21 focused review-fix run (`AppRuleRestrictionComparatorTest`,
  `AppRuleContributorTest.snapshotRuleEvaluationExposesTheCurrentUseDayBreakdownForCards`,
  `CreateAppRuleContributorEditorTest`): 13 tests passed, 0 failed, 0 errors and 0 skipped.
  Full `testFullDebugUnitTest`: 180 tests, 179 passed and 1 failed. The only failure is the known
  fixed-base `ScriptLanguageTest.matchesRegexSupportsCommonFlags`; no ticket 04 test failed.
- `assembleFullDebug`, `assemblePlaystoreDebug`, `assembleFdroidDebug` and `lintFullDebug` passed
  with `C:\Users\DELL\.jdks\jbr-21.0.11`. Lint retains the repository's existing translation and
  baseline warnings. `git diff --check` passes.
- `adb devices` reported no attached device or emulator on 2026-08-18. The reproducible device
  procedure and evidence are recorded in `.scratch/app-rules/evidence/ticket-04-device-verification.md`;
  the unexecuted device run is a confidence limitation, not an implementation blocker.
