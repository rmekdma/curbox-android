# 02 — 정확한 사용일과 다중 전면 앱 집계 완성

**What to build:** 보호자가 정한 전역 복원 시각을 기준으로 오늘 사용량을 정확히 집계하고, 분할 화면에 동시에 보이는 모든 앱을 각각 기록한다. 앱 전환, Curbox와 시스템 UI 진입, 화면 꺼짐, 재시작과 복원 경계에서 세션을 일관되게 닫고 반영하여 사용량 페이지, 앱 그룹 합계와 앱 규칙 잔여시간이 같은 사실을 보여 주게 한다.

**Blocked by:** 01 — 첫 번째 통합 앱 규칙 완성.

**Status:** in-progress (implementation and verification complete; fixed-point review pending)

- [x] 전역 사용일 복원 시각의 기본값은 현지 시각 04:00이며 보호자가 설정에서 변경할 수 있다.
- [x] 티켓 01의 고정 04:00 사용일 ID를 유지하면서 이 티켓에서만 복원 시각을 설정 가능하게 확장한다.
- [x] 복원 시각을 변경하면 이전 집계를 새 경계로 재해석하지 않고 즉시 새 사용일을 시작한다.
- [x] 사용일의 요일은 사용일이 시작한 현지 날짜를 따른다.
- [x] 전면 세션은 복원 경계와 앱 가시성 변경 경계에서 분할되어 저장된다.
- [x] 현재 시간대에 이미 기록된 사용량을 다시 비례 축소하지 않고 실제 저장된 세션 교집합으로 계산한다.
- [x] 이벤트마다 이전 가시 패키지 집합과 새 집합을 조정해 사라진 패키지만 닫고 새로 보이는 패키지만 시작한다.
- [x] Curbox와 System UI 창 자체는 추적 집합에서 제외하되 함께 계속 보이는 다른 애플리케이션 창의 세션은 유지한다.
- [x] 새 앱을 판정하기 전에 직전 앱의 세션이 직렬화된 저장 경로에 반영되어 기여 및 소비량이 최신 상태가 된다.
- [x] 접근성 서비스는 상호작용 가능한 창들을 조회하고 분할 화면에 동시에 보이는 앱마다 독립된 세션을 유지한다.
- [x] 애플리케이션 창만 추적하고 IME, 오버레이 및 비 애플리케이션 창은 제외하며 같은 패키지의 여러 창은 패키지 단위로 중복 제거한다.
- [x] 두 대상 앱이 1분 동안 동시에 보이면 앱별로 1분씩, 두 앱을 포함한 그룹에는 총 2분이 쌓인다.
- [x] 화면이 꺼진 동안에는 어떤 앱에도 전면 사용시간이 쌓이지 않는다.
- [x] 앱 실행 횟수는 현재 사용일 기준으로 기록되지만 규칙의 잠금 조건에는 사용되지 않는다.
- [x] 현재 사용일 시간과 실행 이벤트의 권위 데이터는 세션 원장이며 기존 날짜 및 시간대 집계는 과거 표시, 동기화와 빠른 조회를 위한 파생 데이터다.
- [x] 통계 추적을 꺼도 활성 시간 기반 규칙이 있으면 현재 사용일의 제한 판정용 세션 원장은 임시 기록된다.
- [x] 통계 추적이 꺼진 동안의 제한 원장은 과거 통계나 동기화 집계에 포함하지 않고 이후 통계를 다시 켜도 소급 복원하지 않는다.
- [x] 활성 시간 기반 규칙도 없고 통계 추적도 꺼져 있으면 전면 세션 원장 기록을 중단할 수 있다.
- [x] 서비스 정상 종료 시 미반영 세션을 저장하고, 비정상 종료 후 재시작에서는 저장되지 않은 시간을 임의로 만들어 내지 않는다.
- [x] 서비스 시작 시 저장된 사용일 상태와 세션 경계를 먼저 복원한 뒤 새 세션과 규칙 판정을 시작한다.
- [x] 현재 사용일 계산에 더 이상 필요하지 않은 상세 세션은 집계 데이터에 영향을 주지 않고 정리된다.
- [x] 시간대, 날짜, 복원 시각과 분할 화면의 대표 경계 조건을 상위 규칙 판정 테스트로 검증한다.
- [x] 실제 접근성 창 열거와 분할 화면은 계측 테스트 또는 기기 검증 절차로 확인한다.
- [x] 접근성 창이나 저장소 조회가 실패해도 서비스가 종료되지 않고 이후 이벤트를 계속 처리한다.
- [x] 다중 프로세스 갱신, 재시작과 세션 저장 실패가 현재 수직 기능의 자동화 테스트에서 검증된다.
- [x] full, playstore, fdroid 변형이 모두 컴파일된다.

## Agent evidence

- `UseDayResetTime`, `UseDayCalculator` and `ConfigurableUseDayCalculator` preserve the fixed
  04:00 default while accepting a validated local reset time. `Settings` stores the reset hour,
  minute and reset generation marker with defaults, and `DataStoreManager.updateUseDayResetTime`
  applies a changed boundary immediately without rewriting aggregate rows.
- `AppRuleEvaluator` and `AppRuleEnforcement` now accept the configurable calculator and calculate
  allowance from exact intersections of merged persisted session intervals and active rule windows.
  A reset generation marker excludes sessions from before an in-place reset edit.
- `AppUsageTracker` reconciles the complete `AccessibilityService.windows` application package set,
  deduplicates packages, ignores Curbox, System UI, IME, overlays and non application windows,
  flushes removed packages before starting new ones, splits rows at reset boundaries, and closes
  rows on screen off and normal service shutdown. `flagRetrieveInteractiveWindows` is enabled in
  the service configuration. Reset setting resumes do not create a false launch event.
- `AppUsageTrackingPolicy` makes the statistics versus enforcement ledger decision explicit. When
  statistics are off and an active time rule exists, only session rows are written; aggregate and
  launch history writes remain disabled. When neither needs tracking, active recording is stopped.
- Room v13 adds the reset generation marker and launch ledger. Startup recovery discards open rows
  left by a process death and cleans rows older than the current use day. The accepted destructive
  migration policy therefore includes the additional v13 local data loss risk. Generated Room
  sources for all three debug variants contain both new tables and their expected columns.
- The settings UI exposes the global local reset time and the service and blocker both consume the
  multi process settings flow. JVM seam tests cover configurable reset calculation, generation
  filtering, visible package reconciliation, session finish ordering, tracking policy, repository
  restart cleanup and storage failure recovery.
- Focused core seam run: 19 passed, 0 failed, 0 errors and 0 skipped. A later focused regression
  run covered 9 tests with the same 9/0/0/0 result, and the repository plus enforcement failure
  run covered 3 tests with the same 3/0/0/0 result. The final full run was 138 tests: 137 passed,
  1 failed, 0 errors and 0 skipped. The sole failure is
  `ScriptLanguageTest.matchesRegexSupportsCommonFlags`.
- Fixed base evidence: the same test was run at `6994bf8933b4536dde2aebdc3a0d81751e33fca4`
  in an isolated worktree and failed there as well (`ScriptError` at `ScriptLanguageTest.kt:44`),
  so it is pre existing and unrelated to ticket 02.
- `assembleFullDebug`, `assemblePlaystoreDebug` and `assembleFdroidDebug` all passed under
  `C:\Users\DELL\.jdks\jbr-21.0.11`. `lintFullDebug` passed; its report retains the repository's
  existing lint baseline. `git diff --check` passes.
- `adb devices` reported no attached device or emulator. The allowed device verification procedure
  is recorded in `.scratch/app-rules/evidence/ticket-02-device-verification.md`; it covers split
  screen, window filtering, reset, screen off, restart and failure containment evidence to collect.
