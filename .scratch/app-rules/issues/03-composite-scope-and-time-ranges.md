# 03 — 복합 적용 범위와 시간 구간 완성

**What to build:** 보호자가 모든 앱과 여러 앱 그룹을 포함하고 다른 그룹을 제외해 규칙 범위를 구성하며, 여러 요일과 여러 시간 구간에 하나의 사용 가능 시간을 나누어 쓰게 한다. 자정 통과, 24시간 구간과 겹친 구간을 포함한 모든 시간 의미를 설정 UI부터 접근성 차단 판정까지 일관되게 제공한다.

**Blocked by:** 02 — 정확한 사용일과 다중 전면 앱 집계 완성.

**Status:** review-pending

- [x] 규칙의 포함 대상에는 모든 앱과 하나 이상의 앱 그룹을 함께 선택할 수 있다.
- [x] 규칙의 제외 대상에는 하나 이상의 앱 그룹을 선택할 수 있다.
- [x] 최종 적용 범위는 포함 대상 합집합에서 제외 대상 합집합을 뺀 결과이며 제외가 항상 우선한다.
- [x] 모든 앱은 이후 설치되는 전면 실행 가능 앱을 별도 규칙 수정 없이 포함한다.
- [x] Curbox, 런처, System UI와 현재 키보드는 포함 방식과 저장된 과거 데이터에 관계없이 모든 규칙의 최종 적용 범위에서 전역 차감된다.
- [x] 내부 필수 예외 앱은 앱 그룹 선택 화면에서도 선택할 수 없다.
- [x] Android 설정 앱은 일반 전면 실행 앱과 마찬가지로 보호자가 포함하거나 제외할 수 있다.
- [x] 보호자는 규칙에 하나 이상의 요일과 하나 이상의 시작 및 종료 시각 구간을 설정할 수 있다.
- [x] 시작 시각과 종료 시각이 같으면 빈 구간이 아니라 24시간 구간으로 판정된다.
- [x] 자정을 넘는 구간은 하나의 의미 단위로 유지되고 구간이 시작한 요일에 속한다.
- [x] 겹치거나 맞닿는 구간은 사용량을 중복 소비하지 않는 합집합으로 판정된다.
- [x] 한 규칙의 여러 활성 구간은 현재 사용일의 사용 가능 시간과 사용량을 공유한다.
- [x] 규칙의 활성 구간 밖에서 발생한 대상 앱 세션은 전역 통계에는 남고 규칙 소비량에는 포함되지 않는다.
- [x] 여러 규칙의 적용 범위가 겹치면 각 규칙이 독립적으로 소비하며 하나라도 거부할 때 앱이 차단된다.
- [x] 시간 선택 UI는 별도 하루 옵션 없이 기존 Material 시간 선택 흐름과 요일 선택 패턴을 재사용한다.
- [x] 모든 앱 포함 및 특정 그룹 제외, 22:00부터 06:00, 사용 가능 시간 0인 규칙이 해당 범위만 정확히 차단한다.
- [x] 범위와 시간 변경의 제한 강화 및 약화가 설정 변경 지연에서 보수적으로 분류된다.
- [x] 대상 포함 또는 제외 그룹을 참조하는 규칙이 있으면 그룹 단순 삭제를 거부하고 의존 규칙을 안내한다.
- [x] 보호자가 대상 그룹 삭제를 계속하려면 참조 제거 또는 의존 규칙 삭제 중 하나를 명시적으로 선택해야 한다.
- [x] 검증되지 않은 손상 스냅샷은 실행 중인 유효 제한 스냅샷을 교체하지 않는다.
- [x] 콜드 스타트부터 대상 참조가 손상된 규칙은 오류 상태로 표시하고 알 수 없는 앱까지 임의로 차단하지 않으며 필수 관리 경로를 유지한다.
- [x] 동적 설치 앱, 삭제된 앱, 빈 범위와 깨진 그룹 참조가 서비스 충돌 없이 정의된 결과를 낸다.
- [x] 범위 집합 연산과 모든 시간 구간 조합이 상위 규칙 판정 테스트로 검증된다.
- [x] 생성 UI부터 실제 차단 화면까지 대표 복합 규칙 시나리오가 검증된다.
- [x] 설정 변경 및 패키지 설치 갱신이 서비스 프로세스에 반영되고 개별 범위 계산 실패가 서비스를 종료하지 않는지 검증된다.
- [x] full, playstore, fdroid 변형이 모두 컴파일된다.

## Agent evidence

- `AppRuleScope.resolve` computes dynamic launchable all-apps plus included-group union, then
  subtracts excluded-group union and the Curbox, launcher, System UI and current IME essentials.
  `AppRulePackageScopeReader` keeps launchable caching independent while rereading launcher and
  IME essentials at every enforcement evaluation; the app-rule picker opts into the same
  exclusions while leaving Android Settings selectable, and legacy picker callers are unchanged.
- `AppRuleSchedule` preserves start-weekday overnight windows, treats equal endpoints as 24 hours,
  and merges overlapping or touching windows before intersecting persisted sessions. `AppRuleEvaluator`
  shares one allowance across a rule's active windows, leaves outside-window sessions to global
  statistics, and applies overlapping rules independently with any-deny enforcement.
- `AppRuleSnapshot.validate`, `deleteTargetGroup`, and the app-rule coordinator preserve the last
  valid runtime snapshot, report invalid cold-start configuration without blocking unknown apps,
  and require an explicit reference-removal or dependent-rule-deletion choice. Mixed legacy
  `appGroupId` and composite references are both counted once. Compatibility normalization
  migrates ticket 01 target and time fields into the canonical scope and range list. The editor and
  groups screen expose invalid state and use ViewBinding plus Material controls for composite CRUD.
- `CreateAppRuleFragment` uses the existing `MaterialTimePicker` flow and ViewBinding range rows
  with button summaries; no platform `TimePicker` remains in the rule editor. Receiver setup is
  transactional and cleanup unregisters only registrations that completed successfully.
- `AppRuleRestrictionComparatorTest` covers scope and schedule strength in both directions. The
  focused JBR21 app-rule run covered 31 tests: 31 passed, 0 failed, 0 errors and 0 skipped. The
  final full JBR21 run covered 160 tests: 159 passed, 1 failed, 0 errors and 0 skipped. The sole
  failure is `ScriptLanguageTest.matchesRegexSupportsCommonFlags`, also present at fixed base
  `3c6ef5d8` and unrelated to ticket 03.
- `assembleFullDebug`, `assemblePlaystoreDebug` and `assembleFdroidDebug` all succeeded under
  `C:\Users\DELL\.jdks\jbr-21.0.11` after the final source change. `lintFullDebug` completed
  successfully; its report retains the repository baseline (889 errors, 581 warnings and 7
  hints). `git diff --check` passes for ticket files.
- The SDK `adb devices` command reported no attached device or emulator. The exact composite
  scope and time-window procedure is recorded in `.scratch/app-rules/evidence/ticket-03-device-verification.md`.
