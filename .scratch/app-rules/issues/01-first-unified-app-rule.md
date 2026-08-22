# 01 — 첫 번째 통합 앱 규칙 완성

**What to build:** 보호자가 중립 앱 그룹을 만들고, 그 그룹에 요일과 단일 시간 구간 및 직접 입력한 사용 가능 시간을 적용한 앱 규칙을 만든 뒤, 대상 앱이 실제 사용량을 소비하고 한도에서 차단되는 첫 번째 완전한 경로를 제공한다. 새 앱 그룹과 앱 규칙 모델, 고정 04:00 사용일 경계, 현재 사용일 전면 세션 저장, 설정 UI, 접근성 서비스 판정과 차단 화면을 이 동작에 필요한 범위에서 함께 도입한다. 기존 결합형 앱 그룹은 호환 경계를 통해 계속 읽을 수 있어야 한다.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 앱 그룹은 이름과 전면 실행 가능한 앱 목록만 저장하며 자체 일정이나 사용량 제한을 갖지 않는다. Evidence: `AppRuleAppGroup` has only `id`, `name`, and `selectedPackages`.
- [x] 앱 그룹과 앱 규칙은 이름 변경에도 유지되는 불변 ID를 가지며 새 생성과 복사는 새 ID를 발급한다. Evidence: `create`/`copyWithNewId` plus `AppRuleEvaluatorTest.createAndCopyIssueNewStableIds`.
- [x] 앱 선택 화면에는 런처에서 전면 실행할 수 있는 앱만 표시되고 같은 앱을 여러 그룹에 넣을 수 있다. Evidence: `SelectAppsActivity` uses `LauncherApps.getActivityList` and strict selection cleanup; groups do not enforce package uniqueness.
- [x] 보호자는 이름, 활성 상태, 요일, 단일 시작 및 종료 시각, 앱 그룹 하나와 필수 사용 가능 시간을 가진 앱 규칙을 생성하고 수정하고 삭제할 수 있다. Evidence: `CreateAppRuleFragment` ViewBinding editor and DataStore snapshot updates.
- [x] 사용 가능 시간은 전역 기본값 없이 보호자가 직접 입력하며 0분도 유효하다. Evidence: `AppRule.allowedMinutes` has no global fallback; editor accepts zero and evaluator has `zeroAllowanceDeniesImmediatelyDuringActiveInterval`.
- [x] 화면이 켜진 동안 단일 전면 앱의 세션이 Room에 저장되고 앱 전환 및 화면 꺼짐에서 이전 세션이 끝난다. Evidence: `AppUsageTracker` starts/finishes `ForegroundSession` through `RoomCurrentUseDaySessionRepository` on transitions and screen off.
- [x] 첫 수직 경로의 사용일은 현지 시각 04:00부터 다음 날 04:00까지이며 안정적인 사용일 ID로 세션과 허용량을 묶는다. Evidence: `UseDay` and `UseDayTest`; repository and evaluator use `useDayId`.
- [x] 규칙의 활성 시간에 대상 앱을 사용하면 해당 규칙의 사용량이 증가하고 허용량을 모두 쓰면 차단 화면이 나타난다. Evidence: `AppRuleEvaluator` computes session intersection and `AppRuleBlocker` launches `WarningActivity` on denial.
- [x] 사용 가능 시간이 0이면 규칙의 활성 시간에 대상 앱이 즉시 차단된다. Evidence: deterministic zero allowance evaluator test.
- [x] 규칙의 비활성 시간에는 전역 사용량은 기록되지만 해당 규칙 허용량은 소비하지 않는다. Evidence: `AppUsageTracker` records sessions independently; evaluator returns inactive with zero rule consumption outside its window.
- [x] 같은 앱에 적용되는 기본 규칙이 여러 개면 하나라도 허용하지 않을 때 차단된다. Evidence: `AppRuleEvaluatorTest.multipleApplicableRulesUseAnyDenySemantics`.
- [x] 새 설정 필드는 오래된 JSON에서 안전한 기본값으로 읽히고 기존 결합형 앱 그룹 설정을 잃지 않는다. Evidence: defaulted `Settings.appRuleSnapshot` and `AppRuleRestrictionComparatorTest.oldJsonWithoutNewSnapshotKeepsLegacyGroups`.
- [x] 앱 그룹과 앱 규칙은 하나의 원자적인 제한 구성 스냅샷으로 검증, 지연 및 적용되어 중간의 깨진 ID 참조가 서비스에 노출되지 않는다. Evidence: `AppRuleSnapshot.validate`, `DataStoreManager.updateAppRuleSnapshot`, and last-valid runtime snapshot retention.
- [x] 새 제한 설정은 기존 설정 변경 지연의 보수적인 판정과 새 규칙 갱신 경로를 거친다. Evidence: `GatedSettingsField.APP_RULES`, comparator coverage, and refresh broadcast/collector path.
- [x] 대기 중인 스냅샷이 있어도 후속 편집은 현재 실제 적용 중인 스냅샷과 비교하고 새 대기 후보가 기존 후보를 원자적으로 교체한다. Evidence: `updateGated` compares live settings and replaces the field's pending candidate; comparator tests cover weakening/strengthening.
- [x] Room 버전 변경과 파괴적 마이그레이션이 적용되며 깨끗한 설치에서 새 세션 저장소가 정상 생성된다. Evidence: `AppDatabase` version 11 registers `ForegroundSessionEntity` and retains `fallbackToDestructiveMigration()`; all three debug APKs assemble.
- [x] 규칙 판정은 Android UI와 분리된 하나의 상위 도메인 경계에서 결정론적으로 단위 테스트할 수 있다. Evidence: `AppRuleEvaluator` and `AppRuleEnforcement` JVM seam tests.
- [x] 생성, 수정, 사용, 한도 도달, 차단까지의 대표 시나리오가 자동화 테스트로 검증된다. Evidence: 18 focused app-rule tests passed, including evaluator and repository-boundary behavior.
- [x] 다중 프로세스 설정 갱신과 저장 실패가 접근성 서비스를 종료하지 않는 동작이 이 수직 경로에서 검증된다. Evidence: `AppRuleSnapshotCoordinatorTest.serviceObserverContinuesReceivingValidSnapshotUpdates` proves the service-facing observer continues to receive valid flow snapshots; `AppRuleEnforcementTest.storageFailureDoesNotAbortTheNextEnforcementDecision` proves a repository failure is isolated and the next decision is processed. `AppRuleBlocker` retains the last valid snapshot and keeps service event failures inside logged containment boundaries; the Android process boundary remains wired through the existing multi-process `DataStoreManager` and Room repository.
- [x] 공유 소스 변경 후 full, playstore, fdroid 변형이 모두 컴파일된다. Evidence: `assembleFullDebug`, `assemblePlaystoreDebug`, and `assembleFdroidDebug` all succeeded on JBR 21.0.11.
