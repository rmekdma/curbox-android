# App-rule enforcement canonical baseline and inventory

이 문서는 app-rule enforcement 프로젝트의 connected-suite 기준선, 실패 inventory와 ticket 01–22
상태를 하나로 묶는 정본이다. 여기의 실패는 관찰된 RED evidence이며, 이 문서 자체는 제품
동작이 고쳐졌다고 주장하지 않는다. 다음 ticket은 이 inventory의 stable identifier와 primary
owner를 그대로 사용한다.

## 기준선 두 개

### Historical starting evidence

ticket 18의 implementation handoff가 보존한 시작 기준선은 다음과 같다.

| 항목 | 기록 |
| --- | --- |
| Source revision | `db1066a00433a58f1c2f782f88ae7c9d461eea77` (`db1066a0`) |
| Device | serial `T811MA256GB23418064398`, model `iPlay50_mini_Pro`, Android 13 / API 33 |
| Invocation | `connectedFullDebugAndroidTest` full-suite run으로 기록됨. 당시 정확한 shell transcript와 wall-clock timestamp는 보존되지 않음 |
| Evidence date | 2026-09-07, ticket 18 implementation evidence |
| Result | 89 tests, 65 passed, 24 failed |
| Historical category counts | debug fixture 1, callback flush 2, long boundary 6, recheck/visibility 14, Guardian lifecycle 1 |
| Source record | [ticket 18 implementation evidence](../../.scratch/app-rule-enforcement/issues/18-phase2-fault-cancellation-closure.md#implementation-evidence--replacement-agent-2026-09-07) |

이 기록은 프로젝트의 historical starting evidence로 남긴다. 정확한 timestamp가 없다는 이유로
추정 시각을 채우지 않으며, 다음 실행이 canonical baseline을 supersede한다.

### Superseding canonical run

historical run의 timestamp 공백을 닫기 위해 현재 branch의 unfiltered run을 새로 기록했다.

| 항목 | 기록 |
| --- | --- |
| Evidence-generation revision | `1af45acd0870e46a157adab38ba4c475ab835d9f` (`1af45acd`) |
| Host command | PowerShell: `$env:JAVA_HOME='C:\Users\DELL\.jdks\jbr-21.0.11'`; `.\gradlew.bat connectedFullDebugAndroidTest` |
| Exact command | `.\gradlew.bat connectedFullDebugAndroidTest` |
| Host run start | `2026-09-09T23:56:46.7064251+09:00` |
| Host run end | `2026-09-10T00:00:47.1651995+09:00` |
| Gradle / JVM | Gradle 8.13, JBR 21.0.11 (`C:\Users\DELL\.jdks\jbr-21.0.11`) |
| Host | Windows 10 amd64, PowerShell |
| Device | serial `T811MA256GB23418064398`, model `iPlay50_mini_Pro`, Android 13 / API 33 |
| Build fingerprint | `Alldocube/iPlay50_mini_Pro/iPlay50_mini_Pro:13/TP1A.220624.014/1699256002:user/release-keys` |
| Result XML | `app/build/outputs/androidTest-results/connected/debug/flavors/full/TEST-iPlay50_mini_Pro - 13-_app-full.xml` |
| XML report timestamp | `2026-09-09T15:00:43` (JUnit XML field; timezone is not encoded in that field) |
| Result | 91 tests, 67 passed, 24 failed, 0 errors, 0 skipped |
| Gradle exit | 1, because the 24 observed tests failed |

현재 run은 ticket 19에서 추가된 두 connected tests를 포함한다. 두 tests는 통과했고, historical
24 failure의 이름은 그대로 유지됐다. 그러므로 현재 run은 historical `89/65/24`를
`91/67/24`로 supersede하는 canonical baseline이다. 두 기준선 모두 실패가 고쳐졌다는
의미가 아니며, filtered 또는 focused run은 이 표의 total에 합치지 않는다.

## Primary-owner seam

각 failed case는 첫 번째 failing assertion이 속한 책임에 따라 정확히 하나의 owner를 가진다.

- `T21-LONG-BOUNDARY-SEMANTICS`: evidence age, provenance와 long-boundary decision semantics.
- `T22-RECHECK`: delayed or changed evidence의 recheck scheduling, retry, cancellation과
  re-evaluation ordering. `recentForegroundEvidenceSurvivesAStaleOtherApplicationWindow`와
  `packageLessUnknownSlotRetriesObservationThreeTimesThenStops`는 이름에 stale/unknown이
  있어도 failing assertion이 boundary scheduling 또는 retry sequence이므로 여기에 둔다.
- `T23-VISIBILITY-OWNERSHIP`: application-window selection, root availability와 target
  ownership. `recentForegroundEvidenceSurvivesWindowProviderException`은 failing assertion이
  provider/root availability에 따른 target boundary 보존이므로 여기에 둔다.
- `T24-CALLBACK-FLUSH`: callback return, visible-session persistence commit와 evaluator
  ordering.
- `T25-GUARDIAN-LIFECYCLE`: Guardian activity의 repeated-denial lifecycle.
- `T26-DEBUG-FIXTURE`: debug package or connected-test fixture setup.

따라서 stale, empty 또는 null이라는 단어가 있다는 이유만으로 T23에 넣지 않는다. assertion이
selection, root availability 또는 target ownership이면 T23이고, evidence age 또는 decision
semantics이면 T21, recheck scheduling/retry이면 T22다. 이 규칙은 ticket 21–23의 scope를
겹치지 않게 하며 product policy나 threshold를 새로 만들지 않는다.

## Failure inventory

Stable identifier는 `test class::test method`이다. 각 행은 canonical XML에서 failure node가
확인된 한 case이며, 같은 case는 아래에서 한 번만 센다.

### T21-LONG-BOUNDARY-SEMANTICS — 6 cases

| Stable identifier | Primary classification |
| --- | --- |
| `AppRuleBlockerLongBoundaryRedTest::successfulWindowThenUncertainReadsCarryStaleProvenanceToBoundedR5Decision` | evidence age / R5 decision |
| `AppRuleBlockerLongBoundaryRedTest::ar002R05_emptyWindowAfterThirtySecondGrantUsesPersistedDecisionAndWarning` | empty-window long boundary |
| `AppRuleBlockerLongBoundaryRedTest::ar002R05_nullRootAfterThirtySecondGrantUsesPersistedDecisionAndWarning` | null-root long boundary |
| `AppRuleBlockerLongBoundaryRedTest::ar002R05_staleWindowAfterThirtySecondGrantUsesPersistedDecisionAndWarning` | stale-window long boundary |
| `AppRuleBlockerLongBoundaryRedTest::ordinaryBoundaryFailureDeniesStaleEmptyAndNullThenAllowsLaterEventHandling` | boundary failure decision |
| `AppRuleBlockerLongBoundaryRedTest::scheduledBoundaryCancellationIsContainedWithoutEffectsOrRecovery` | boundary cancellation semantics |

Source: [AppRuleBlockerLongBoundaryRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerLongBoundaryRedTest.kt).

### Ticket 21 closure evidence — 2026-09-10

Ticket 21 closes the six stable identifiers above without changing the approved foreground policy.
The production change keeps the host classifier's raw real-event history aligned with the same
facts handed to the serialized worker, restores the existing R5 fail-closed evaluator path for
ordinary evaluator failure, and prevents `observeWorkerEvaluation()` from renewing evidence for a
synthetic request. `failClosedEvaluation()` preserves evaluator-derived eligibility when no rule
applies and still creates zero-remaining denials for applicable rules. Synthetic rechecks do not
renew evidence age while evaluation observation and publication remain intact. The partial-window
fixture in the T21 provenance method represents an unresolved slot with no currently known package;
the separate known-other-package ownership case remains the T23 contract.

| Verification | Environment and result |
| --- | --- |
| Focused JVM | `testFullDebugUnitTest` with `ForegroundEvidenceContractTest` and `SerializedDecisionWorkerTest`: 53 tests, 53 passed, 0 failures, 0 errors, 0 skipped. |
| Focused connected | `connectedFullDebugAndroidTest` filtered to `AppRuleBlockerLongBoundaryRedTest` on `iPlay50_mini_Pro - 13` / Android 13: 10 tests, 10 passed, 0 failures, 0 errors, 0 skipped. The six T21 identifiers and the synthetic timestamp regression all passed. |
| Full JVM | `testFullDebugUnitTest`: 351 tests, 351 passed, 0 failures, 0 errors, 0 skipped. |
| Flavor compile | `assembleFullDebug`, `assemblePlaystoreDebug`, and `assembleFdroidDebug`: all succeeded. |
| Final unfiltered connected | `connectedFullDebugAndroidTest` on `iPlay50_mini_Pro - 13` / Android 13: 92 tests, 89 passed, 3 failures, 0 errors, 0 skipped. |

The final connected failures are unchanged outside T21: `ExampleInstrumentedTest::useAppContext`
is the T26 debug-fixture failure, and
`AppRuleBlockerCallbackFlushOrderingRedTest::foregroundCallbackReturnsBeforeDelayedPersistenceCompletes`
plus `AppRuleBlockerCallbackFlushOrderingRedTest::evaluatorRunsOnlyAfterVisibleSessionFlushIsCommitted`
are the two T24 callback-flush failures. No T21, T22, T23, or T25 failure node was reported in
this final run; that observation does not reassign or close those tickets. The connected result is
not Xiaomi evidence: `Xiaomi Pad Pro 2025 12.7` Android 15/16 remains the ticket 29 device gate.

### T22-RECHECK — 3 cases

| Stable identifier | Primary classification |
| --- | --- |
| `AppRuleBlockerRecheckTest::recentForegroundEvidenceSurvivesAStaleOtherApplicationWindow` | boundary recheck must be scheduled before visibility recovery |
| `AppRuleBlockerRecheckTest::scheduledRecheckReevaluatesGlobalDenialAfterTargetUsageAndGuardianExtra` | scheduled re-evaluation after runtime rule change |
| `AppRuleBlockerRecheckTest::packageLessUnknownSlotRetriesObservationThreeTimesThenStops` | bounded retry sequence |

Source: [AppRuleBlockerRecheckTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt).

### Ticket 22 closure evidence — 2026-09-10

Ticket 22의 세 stable identifier는 기존 keyed recheck scheduler와 serialized decision worker
경로를 변경하지 않고 현재 branch에서 재검증했다. 첫 사례는 stale window recovery보다 boundary
recheck가 먼저 등록되고 만료 후 현재 결정을 수행하는지, 둘째 사례는 runtime rule publication
뒤 scheduled re-evaluation이 새 denial을 한 번의 현재 결과로 반영하는지, 셋째 사례는
package-less unknown slot의 retry sequence가 기존 bounded sequence에서 멈추는지를 확인한다.
T23의 window selection/root availability/target ownership 사례는 이 closure에 포함하지 않았다.

| Verification | Environment and result |
| --- | --- |
| Focused connected | `connectedFullDebugAndroidTest` filtered to `AppRuleBlockerRecheckTest` on `iPlay50_mini_Pro - 13` / Android 13, full flavor: 37 tests, 37 passed, 0 failures, 0 errors, 0 skipped. The three T22 identifiers are present in the XML and each is `PASSED`. XML timestamp: `2026-09-10T01:15:18` (timezone not encoded). |
| Focused JVM | `testFullDebugUnitTest` with `AppRuleScheduleCompositeTest` and `SerializedDecisionWorkerTest`: 33 tests, 33 passed, 0 failures, 0 errors, 0 skipped. |
| Supporting scheduler assertions | The connected class also passed stale-plan cancellation, real foreground switch cancellation, scheduler-post recovery, coalesced wake completion, and no-duplicate external outcome checks. |
| Production change | None. Existing behavior already satisfied the T22 contract; no interval, policy, threshold, device substitution, or architecture decision was added. |

This focused result does not change the historical 91-test canonical inventory. It closes only the
three T22 ownership cases; the remaining inventory ownership and the Xiaomi gate stay separate.

### Latest full-suite verification after Ticket 22 — 2026-09-10

The required final suites were run after the focused T22 verification. These results are a new
execution record and do not rewrite the historical 91-test inventory or the earlier T21 closure
run.

| Verification | Environment and result |
| --- | --- |
| Full JVM | `testFullDebugUnitTest`: 351 tests, 351 passed, 0 failures, 0 errors, 0 skipped. |
| Full connected | `connectedFullDebugAndroidTest` on `iPlay50_mini_Pro - 13` / Android 13, full flavor: 92 tests, 88 passed, 4 failures, 0 errors, 0 skipped. XML timestamp: `2026-09-10T01:24:14` (timezone not encoded). |

The four full-connected failures classify against Ticket 20 as T24 callback flush (2), T25
Guardian lifecycle (1), and T26 debug fixture (1). T21, T22 and T23 had no failure node in this
run. The newly observed T25 failure is recorded as current verification evidence; it does not
change T22 ownership or claim that the T25 case is fixed. This run is on iPlay50 and is not the
Ticket 29 Xiaomi Pad Pro 2025 12.7 Android 15/16 gate.

### T23-VISIBILITY-OWNERSHIP — 11 cases

| Stable identifier | Primary classification |
| --- | --- |
| `AppRuleBlockerRecheckTest::essentialOverlayClearsForegroundEvidenceAndDoesNotStartGuardianTwice` | essential overlay and stale target ownership |
| `AppRuleBlockerRecheckTest::essentialEventEvaluatesTargetIdentifiedByApplicationWindow` | target selection under essential event |
| `AppRuleBlockerRecheckTest::essentialEventEvaluatesEveryDedupedApplicationWindowPackage` | deduped application-window ownership |
| `AppRuleBlockerRecheckTest::reconnectEvaluatesKnownApplicationWindowsWithoutForegroundOrActiveRoot` | reconnect window/root availability |
| `AppRuleBlockerRecheckTest::overlayEvaluationUsesEveryPackageFromTheOriginalWindowSnapshot` | original window snapshot ownership |
| `AppRuleBlockerRecheckTest::activeZeroAllowanceRuleStartsGuardianApprovalForCurrentPackage` | current package target ownership |
| `AppRuleBlockerRecheckTest::essentialRootAndReconnectProcessVisibleTargetWithoutDuplicateGuardian` | essential root and reconnect target ownership |
| `AppRuleBlockerRecheckTest::suspendedEvidenceResumesFromModuleVisibleOutcomeUnderEssentialRoot` | suspended evidence and essential-root visibility |
| `AppRuleBlockerRecheckTest::recentForegroundEvidenceSurvivesWindowProviderException` | provider/root availability |
| `AppRuleBlockerRecheckTest::splitScreenKnownWindowsKeepIndependentBoundaryJobsWhenOneRootIsUnknown` | split-screen window ownership |
| `AppRuleBlockerRecheckTest::allAppsRuleEvaluatesCurrentPackageWhenLauncherListingOmitsIt` | current package ownership for all-apps scope |

Source: [AppRuleBlockerRecheckTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerRecheckTest.kt).

### T24–T26 — 4 cases

| Owner | Stable identifier | Primary classification |
| --- | --- | --- |
| T24 | `AppRuleBlockerCallbackFlushOrderingRedTest::foregroundCallbackReturnsBeforeDelayedPersistenceCompletes` | callback handoff before delayed persistence |
| T24 | `AppRuleBlockerCallbackFlushOrderingRedTest::evaluatorRunsOnlyAfterVisibleSessionFlushIsCommitted` | evaluator before visible-session commit |
| T25 | `GuardianApprovalActivityLifecycleTest::repeatedDenialReusesTheVisibleApprovalScreen` | repeated Guardian activity lifecycle |
| T26 | `ExampleInstrumentedTest::useAppContext` | debug application-id fixture |

Sources: [AppRuleBlockerCallbackFlushOrderingRedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerCallbackFlushOrderingRedTest.kt),
[GuardianApprovalActivityLifecycleTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/ui/activity/GuardianApprovalActivityLifecycleTest.kt),
and [ExampleInstrumentedTest.kt](../../app/src/androidTest/java/neth/iecal/curbox/ExampleInstrumentedTest.kt).

### Count reconciliation

| View | Counts |
| --- | --- |
| Historical categories | debug fixture `1` + callback flush `2` + long boundary `6` + recheck/visibility `14` + Guardian lifecycle `1` = `24` |
| Canonical primary owners | T21 `6` + T22 `3` + T23 `11` + T24 `2` + T25 `1` + T26 `1` = `24` |
| Canonical XML | `91` total = `67` passed + `24` failed + `0` errors + `0` skipped |

The owner inventory is a partition: no stable identifier appears in two owner sections, and every
one of the 24 canonical failure nodes appears once.

## Ticket and phase reconciliation

### Tickets 01–19

| Ticket | Canonical status | Evidence or interpretation |
| --- | --- | --- |
| 01 | `done` | Approved 13-row foreground policy matrix and R5 A selection. |
| 02 | `done` | Long-boundary RED contract preserved as evidence; not a fix claim. |
| 03 | `done` | Deterministic AR010 interleaving evidence recorded. |
| 04 | `done` | Virtual doze/wake RED contract preserved. |
| 05 | `done` | Callback flush RED contract preserved; current two failures belong to T24. |
| 06 | `done` | Destroy/fault RED contract preserved; later closure is ticket 18. |
| 07 | `done` | Phase 1/2 architecture contract approved on 2026-09-01. |
| 08 | `done` | Evidence contract and production/deterministic adapters implemented. |
| 09 | `done` | AR001 long-boundary implementation slice recorded; ticket 21 later records deterministic closure of the six owned cases. |
| 10 | `done` | Complex foreground policy and caller reduction recorded; current T23 cases remain open. |
| 11 | `done` | Serialized decision worker happy path recorded. |
| 12 | `done` | Wall-clock scheduler and wake recovery recorded. |
| 13 | `done` | Worker fault containment recorded. |
| 14 | `done` | AR010 serialized publication path verified. |
| 15 | `done` | Final Phase 2 destroy/reconnect measurement contract recorded. |
| 16 | `done — NO-GO` | Per-package coordinator not needed; independent deadlines were observed without a coordinator. |
| 17 | `done — historical conditional NO-GO` | Component-boundary decision was superseded by the full-service revisit in ticket 19; its old review-open wording is not current status. |
| 18 | `done` | `AppRuleBlockerDestroyFaultRedTest` is `21/21`; the full-suite 24 failures are unrelated residual evidence. |
| 19 | `done — NO-GO; architecture gate closed` | Full-service lifecycle evidence closed the canonical decision; no lifecycle host is warranted. |

The detailed ticket records remain in [.scratch/app-rule-enforcement/issues](../../.scratch/app-rule-enforcement/issues).
Ticket 17's component-only limitation and ticket 19's later full-service decision are both retained;
the latter is authoritative for the current Phase 4 status.

### Tickets 20–22

| Ticket | Canonical status | Evidence or interpretation |
| --- | --- | --- |
| 20 | `done` | Historical connected-suite inventory and documentation baseline are frozen; the historical 24 failures remain historical evidence. |
| 21 | `done` | The six T21 stable identifiers have deterministic closure evidence above. T22/T23 ownership and the Xiaomi device gate remain separate. |
| 22 | `done` | The three T22 stable identifiers passed the focused connected and JVM verification above. No production change was needed; T23 visibility/ownership remains separate. |

### Phases 0–4

| Phase | Reconciled status | Open boundary |
| --- | --- | --- |
| Phase 0 | `complete — policy and deterministic RED-contract stage` | Its failures are inputs to later implementation tickets, not fixed results. |
| Phase 1 | `T21/T22 deterministic closure recorded; verification open` | T23 retains the 11 visibility/ownership cases; T24/T25/T26 and Xiaomi device verification remain open. |
| Phase 2 | `implementation and fault/cancellation contracts recorded done; measurement decisions open` | T24 owns callback flush; T27 owns callback/decision p95; T28 owns numeric drain-budget decision. |
| Phase 3 | `done — NO-GO` | Do not add a per-package coordinator unless its entry condition is newly reproduced. |
| Phase 4 | `done — NO-GO; architecture gate closed` | Revisit only if production cleanup ordering or containment changes. |

This table separates implementation slices from release/device verification. It does not turn the
historical 24 failures, or the later connected results, into a green release gate.

## Remaining verification work

- Ticket 21's six T21 cases are resolved in the deterministic closure evidence above without changing the approved evidence policy.
- Ticket 22's three recheck cases are resolved in the closure evidence above; ticket 23 owns the
  eleven visibility/ownership cases. Neither ticket may absorb the other owner's identifiers.
- Ticket 24 owns the two callback-flush cases. Ticket 25 owns the one Guardian lifecycle case.
- Ticket 26 owns the one debug-fixture case and must not relabel product failures as fixture failures.
- Ticket 27 must first propose and obtain approval for a p95 measurement protocol; no p95 value is
  currently recorded.
- Ticket 28 must first obtain the explicit numeric drain-budget/completion decision; production
  remains on `RecoveryOnlyStop` and no threshold is inferred here.
- Ticket 29 is last and targets exactly `Xiaomi Pad Pro 2025 12.7` on Android 15 or Android 16.
  The exact installed build/channel must be captured at execution; the current iPlay50 evidence is
  not a substitute.
- `AR004 Xiaomi Pad Pro 2025 12.7 device verification complete` and `reported incident closed` remain open. No
  current evidence claims OEM resolution or release readiness.

## Verification of this record

- The unfiltered command was run as `.\gradlew.bat connectedFullDebugAndroidTest` with the exact
  host timestamps and environment above.
- The JUnit XML parser reported `91` cases, `24` failure nodes, `0` errors, `0` skipped and hence
  `67` passes. The failed class/method pairs match the 24 identifiers in this document.
- The T22 focused XML reported `37` cases, `0` failures, `0` errors and `0` skipped; all three
  T22 identifiers were present and passed. The focused JVM XML reported `33` cases with no
  failures, errors or skips.
- The final post-T22 full JVM XML reported `351` cases, all passed. The final post-T22 connected
  XML reported `92` cases with `88` passes and four failures classified as T24 `2`, T25 `1`, and
  T26 `1`; T22 had no failure node.
- The device properties were read through ADB and match the recorded serial, model, API level and
  build fingerprint.
- Focused or filtered results, including ticket 19's binder test, were not added to the 91-test
  total.
