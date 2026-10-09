# Guardian refactor and code-size investigation

## Scope and snapshots

The size and design comparison is a **historical pinned snapshot** from baseline `15164980` through `2892ab00` (`Fix app rule scheduler recheck recovery`). Its counts and conclusions describe those two commits, not the live checkout. This report follows the repository instructions and the `codebase-design` vocabulary in `SKILL.md` and `DEEPENING.md`: assess Module depth through caller leverage and maintainer locality, not through an implementation-to-interface line ratio.

The scheduler has a later historical addendum at `0b06bab021fd9f854b1f438b8e4880a0c25491d4` (`Fix app rule foreground recheck recovery`). At the start of this reconciliation, that exact commit was `HEAD`. The scheduler and test changes that appeared as uncommitted concurrent work during the original `2892ab00` investigation were committed in `0b06bab0`; they are no longer uncommitted. The only untracked files present at reconciliation start were `docs/architecture/guardian-confirmation-feasibility.md` and `docs/architecture/guardian-launch-feasibility.md`; they were read as design context and left untouched.

Line references in the pinned size comparison and the original analysis in sections 1–5 refer to physical lines at `2892ab00`. The current claim-disposition table in section 1, the added lifecycle and persistence findings in section 5, the cleanup table in section 6, and the scheduler addendum were checked at execution HEAD `0b06bab0`; their source references point to that commit. This convention keeps older line numbers useful without presenting them as current locations. The original investigation ran no build, test, ADB, or device operation; this ticket only reconciles documentation and requires no Gradle build.

## Judgment

The launch-workflow refactor demonstrates a scoped improvement in **ownership locality and testability**, while increasing total production source by about 195 lines. A concrete change scenario is replacement-offer fencing while a policy read is suspended: that sequencing now belongs to the workflow and has deterministic Host tests; before extraction, the relevant coroutine and claim logic lived in the Activity. The Activity no longer owns claim/generation/suspend ordering, but a maintainer still traces the Activity adapter, workflow, and pure authorization module. There is no comparative change-task or cognitive-cost evidence, so overall feature-maintenance improvement is partial and unproven; this investigation does not establish that the extraction is the final or optimal design.

The host-test additions met their **missing integration-coverage goal**, while leaving a file-organization issue. The five new AppRuleBlocker worker tests cover production result publication, payload identity, permit cleanup, and timeout/retry races that pure workflow tests cannot establish. Their common fixture avoids repeating a large amount of setup, but the tests and fixture now sit inside a 4,632-line general recheck test file. Splitting them would improve locality; deleting them would remove unique race and service-wiring coverage.

The scheduler recovery change at the pinned `2892ab00` snapshot appears to address concrete **wake delivery and stale cancellation failures**, with a 228-line net increase in `AppRuleBlocker.kt`. Most new state has distinct freshness or recovery meaning. At that snapshot, one package set was write-only; the `0b06bab0` follow-up replaced it with registration identity state, resolving that cleanup candidate. The duplicated registration bookkeeping remains a candidate for seam review, not a safe deletion based on this read. The pinned report's advice to wait for the concurrent scheduler work is historical; the later state is described below.

Overall, the refactor did not reduce total source size, but its main growth is attributable to separately valuable behavior and its tests. The actionable work is narrow: separate test-only adapters from production sources, split the new worker integration tests into a cohesive test file, remove a few verified no-value assertions/tests, and review the remaining scheduler registration ledger only if evidence shows a real duplicated responsibility. The measurement harness remains under review while its external artifact consumer is unknown.

## Size attribution

These counts compare baseline `15164980` with the pinned endpoint `2892ab00`; test net changes are source organization and coverage, not production APK growth.

| Area | Baseline → `2892ab00` | Change | Attribution |
|---|---:|---:|---|
| `GuardianApprovalActivity.kt` | 1,977 → 1,915 | −62 | Launch sequencing moved out; Activity retains Android facts and effects. |
| `GuardianApprovalOfferLaunchWorkflow.kt` | absent → 257 | +257 | New Activity-scoped Module, including its claim and generation state. |
| `AppRuleBlocker.kt` | 4,567 → 4,824 | +257 | Of this, `2892ab00` itself is +254/−26 = +228 net for scheduler recovery; earlier guardian timeout work contributes the remainder. |
| `AppRuleBlockerRecheckTest.kt` | 3,317 → 4,632 | +1,315 | Approximately 1,300 net lines are guardian worker/result and race coverage added before the scheduler fix; the latest fix changes the existing scheduler tests as well. |
| `GuardianApprovalActivityLifecycleTest.kt` | — | +209 net | Existing Activity wiring/lifecycle coverage was extended and adjusted. |
| `GuardianApprovalOfferLaunchWorkflowTest.kt` | absent → 513 | +513 | Deterministic tests through the new Host Interface. |
| **Production total** | — | **+452 net** | `−62 + 257 + 257`. |
| **Test total** | — | **+2,037 net** | `+1,315 + 209 + 513`. |
| **All listed files** | — | **+2,489 net** | The majority of growth is test code. |

The commit sequence separates two concerns. `cf32c1d0` extracts the guardian launch workflow; `111bd767` adds workflow review cases. `8a1a3cfe`, `4c86f018`, `44d4634c`, and `587fb7a6` add actual worker result, denial, failed-send cleanup, and timeout-replacement coverage. The last commit in the pinned comparison, `2892ab00`, is scheduler recovery work, not part of the Activity extraction. Later scheduler changes in `0b06bab0` are not included in the historical size totals.

## 1. Guardian workflow extraction

### What moved and what the Activity still knows

Before the extraction, `handleAllowedOffer` owned the offer claim and the whole async sequence. At the pinned commit, `GuardianApprovalActivity.kt:1467` decodes the allowed-result payload, binds the current target package to the offer, and calls `guardianApprovalOfferLaunchWorkflow.accept(...)`. The Activity-scoped Host adapter starts at `:109`; it supplies current request facts, PackageManager lookup, the single DataStore fingerprint read, the synchronous launch guard, outcome effects, and nonfatal logging.

The extracted Module in `GuardianApprovalOfferLaunchWorkflow.kt:68` owns the active `(offerId, identity)` key, generation counter, job cancellation, lookup/read ordering, and stale continuation fencing. The Activity no longer has `confirmationOfferValidationId`; its launch helper at `GuardianApprovalActivity.kt:1501` still reads a fresh platform/identity snapshot and calls `startActivity` immediately after the final synchronous authorization. UI state, broadcasts, and finish handling stay in `:1582`. The Activity lifecycle scope supplies cancellation when the Activity is destroyed.

This realizes the accepted design in `docs/architecture/guardian-launch-feasibility.md:8`: only the attempt claim and sequencing move; the Activity remains the single owner of approval identity and UI state. The production Activity Host and the test Host in `GuardianApprovalOfferLaunchWorkflowTest.kt:407` are two adapters at a real seam. The test adapter substitutes asynchronous operations with controllable values and deferred results, so it exercises the same orchestration without importing Android `Intent` into the algorithm.

The Activity learned less about the launch workflow: it no longer handles suspend points, generations, stale read completions, or claim retain/release decisions. It still knows the current Android lifecycle facts and how each typed outcome affects the UI or service. The split has a clear ownership rationale because these facts and effects belong to the Activity. The new Interface is moderate in size (seven Host operations plus request/state/snapshot/result types), and hides a larger async ordering contract behind one `accept(request)` entry point. This supports the scoped ownership and testability finding above; it does not by itself prove lower maintenance cost across the whole feature.

### Exact branch behavior and tests to preserve

The workflow tests cover synthetic out-of-order A/B offers and A→B→A reuse, stale completions after package or identity changes, settings-read failure, policy invalidation, deadline expiry, missing launch target, start failure, and focus rejection. The Activity tests retain production integration for policy tightening during lookup, retry UI after a failed settings read, and lifecycle exit: `GuardianApprovalActivityLifecycleTest.kt:1198,1549,1799,1942,2083`.

Keep these claim outcomes distinct in any future refactor. The claim is an explicit workflow-owned active attempt; it is not cleared automatically at every return or state transition.

| Situation | Disposition at `0b06bab0` | Evidence |
|---|---|---|
| Missing lookup result | Report lookup failure and retain the claim. The same offer remains claimed until another explicit transition or lifecycle cleanup. | Workflow `:127–130`, test `:364`. |
| Current settings read failure | Release the claim, show retryable failed UI, and notify the service. | Workflow `:134–142`, Activity `:1591`, test `:169`. |
| Policy or evaluation-window invalidation | Release the claim and request read-only re-evaluation. | Workflow `:146–152`, Activity `:1598`, tests `:107` and `:296`. |
| Deadline expiry | Release the claim and report timeout through the retry state. | Workflow `:146–157`, Activity `:1601`, test `:238`. |
| First authorization rejects quiet focus or keyguard state | Release the claim silently when the evaluation window and policy remain current; a repeated offer can be tried again. | Workflow `:146–158`, test `:385`. |
| Final synchronous `Rejected` | Retain the claim and return without a user-facing outcome. | Workflow `:163–175`, test `:275`. |
| Stale identity or package after a suspension | The continuation exits without explicitly releasing its claim. | Workflow `:127`, `:135`, and `:197–204`; tests `:50` and `:210`. |
| Launch success | Release the claim and finish the Activity. | Workflow `:163–166`, Activity `:1607`, workflow test `GuardianApprovalOfferLaunchWorkflowTest.kt:20`. |
| Launch exception | Report failure while retaining the claim. | Workflow `:180–183`, test `:255`. |
| Cancellation | Rethrow `CancellationException`; do not convert it to failure or infer an explicit claim release. | Workflow `:117–126` and `:178–183`; this also follows the repository service-safety invariant. |

Only the branches that call `release(attempt)` release the claim. Missing lookup, stale identity/package exits, the final synchronous `Rejected`, launch exceptions, and cancellation do not call it. Do not describe these outcomes as if every terminal-looking transition clears the claim.

The policy fingerprint is read once after launch-target lookup (`GuardianApprovalActivity.kt:132` and workflow `:129`). Keep that order and the fresh identity/state checks after suspend points. The second Activity check is a time-of-use guard before the Android effect; it should not be removed as “duplicate authorization.” The same synchronous path must reach `startActivity` without a new suspend point.

### Narrow optional cleanup

There is one small duplicated predicate: workflow `isCurrentRequest` (`GuardianApprovalOfferLaunchWorkflow.kt:220`) and Activity `isCurrentGuardianLaunchRequest` (`GuardianApprovalActivity.kt:1562`) both require callbacks to be allowed, confirmation pending, target package equal, and execution identity equal. Both freshness checkpoints are needed at their separate async boundaries, and there is no observed mismatch or failure showing the copies are unsafe. A shared `matches(request)` helper could reduce future conditional drift, but its benefit is small and it couples the two boundaries through a helper. Treat it as optional if these conditions are already being edited together; it is not a standalone priority. Preserve both checks.

Keep `currentRequestState()` separate from `currentSnapshot()`. The former is a cheap identity/pending gate used before Android lookups and after them; the latter reads lifecycle, focus, keyguard, zone, and both clocks for authorization. Collapsing them would either perform platform reads at extra checkpoints or weaken a stale-request check. Likewise, keep the authorization preflight and final synchronous guard distinct. No evidence supports a broader rewrite or a new Gradle module.

## 2. Guardian worker tests inside `AppRuleBlockerRecheckTest.kt`

### What is unique rather than duplicate coverage

Five new test methods share `createConfirmationHostFixture`:

- `actualGuardianWorkerSendsAllowedOfferForAcceptedRequest` (`:1185`) exercises the actual serialized worker and checks the allowed payload’s request identity, policy fingerprint, evaluation timestamps, and active deadline.
- `actualGuardianWorkerSendsRemainingRowsFromItsEvaluation` (`:1328`) checks that the service publishes the denying rows produced by the same evaluation, with matching identities and values.
- `failedAllowedSendCleansPermitBeforeFailedAndAcceptsNextCheck` (`:1447`) observes permit and in-flight callback cleanup at the failed broadcast, then verifies a later check can produce a fresh allowed offer.
- `recoveryAcceptedBeforeTimeoutLockRejectsStaleATimeoutAndKeepsBDeadline` (`:1637`) checks the timeout winner and that check B retains its own deadline.
- `timedOutASendMayArriveAfterRetryBWithoutChangingItsDeadline` (`:1785`) delays A’s timeout send across retry B and verifies that the late send cannot overwrite B’s timeout state.

These tests cover the production AppRuleBlocker → worker → broadcast path. The launch workflow unit tests exercise Activity-side orchestration, while Activity lifecycle tests synthesize service results to test the receiving Activity. Neither substitutes for these tests. The baseline `guardian-confirmation-feasibility.md:98` specifically identified the missing AppRuleBlocker result-publication coverage; the new methods supply that evidence. Keep the behavior and assertions.

### File concentration and a cohesive split

`AppRuleBlockerRecheckTest.kt` already held foreground visibility and lifecycle tests, scheduler and wake tests, and guardian receiver tests. The additions occupy about 1,300 net lines, with five behavior tests and one shared fixture, and the stable file is now 4,632 physical lines. The growth is reasonable for coverage but the file now mixes three review paths: foreground observation, scheduler recovery, and guardian confirmation worker delivery.

The new fixture is centralized, not five copies of a fixture. `createConfirmationHostFixture` (`:2032`) seeds/restores DataStore settings and starts the actual blocker; `ConfirmationHostFixture` (`:4338`) owns gates, broadcasts, outcome latches, permit snapshots, and cleanup. `BroadcastExpectation` and `ExternalEffectSnapshot` start at `:4303` and `:4329`; they make result ordering observable. The new tests reuse existing intent builders and reflection helpers. Do not duplicate these helpers merely to shorten one file.

If the team wants improved locality, the narrow split is:

1. Move the five methods and their two assertion helpers into `AppRuleBlockerGuardianConfirmationTest.kt` in the same `src/androidTest` package.
2. Move `ConfirmationHostFixture`, `BroadcastExpectation`, `ExternalEffectSnapshot`, `createConfirmationHostFixture`, and its settings helper exactly once into `AppRuleBlockerGuardianConfirmationTestSupport.kt`.
3. Move or expose the shared `RecordingService`, intent builders, and reflection helpers once as `internal` test support so both test classes use the same implementation. Keep the existing scheduler/visibility tests in the recheck test class.

This relocates roughly 1,200–1,300 lines into cohesive Android-test files; it removes essentially no behavior or total LOC. The fixture’s use of the real DataStore and worker makes it test-specific, not a candidate production abstraction. It can be split within the existing module and source set; no artificial Gradle module is needed. If that organization change is not currently worth the file churn, preserve the tests as they are rather than delete them.

## 3. Scheduler recovery growth in `AppRuleBlocker.kt`

### Behavior added by `2892ab00`

The scheduler fix contributes +254/−26 = +228 net production lines. It adds per-package source-order rejection around recheck-plan delivery (`AppRuleBlocker.kt:1247`), a host registration ledger and lock (`:399` and `:4150`), coalesced pending wake tracking (`:1363` and `:2129`), and recovery when all main-thread visibility posts fail (`:4008`). It also makes a stale not-visible result cancel only the registration it observed (`:2205` and `:4453`).

The coverage is specific to those cases: post failures retain the absolute boundary; an early wake is re-armed without evaluating before its wall-clock due time; expired relative recovery is advanced; and stale plan or not-visible cancellation cannot remove a replacement registration (`AppRuleBlockerRecheckTest.kt:3327,3373,3469,3780,3839`). These are real asynchronous failure shapes. Removing the extra bookkeeping wholesale would reopen lost-wake or stale-cancellation cases.

The change also separates failure policy between concerns. `AppRuleWakeScheduler` handles AlarmManager, Handler fallback, time conversion, and its own token-checked active registration (`AppRuleWakeScheduler.kt:6`; `AndroidAppRuleWakeScheduler.kt:119` and `:247`). `AppRuleBlocker` handles worker source-order freshness, visible-state evidence, lifecycle generation, and deciding how to recover an unknown observation. Moving all that into the scheduler Adapter would give it service/runtime knowledge it does not currently own.

### Narrow consolidation candidates

At the pinned snapshot, `pendingSchedulerWakePackages` was a write-only Set: it was added and cleared alongside `pendingSchedulerWakeDueAtByPackage`, while decisions derived `pendingWakePackages` from the due map's keys (`AppRuleBlocker.kt:407,1371–1374,2133–2136,2268–2270,3532–3534,4061–4063`). The `0b06bab0` follow-up replaced both structures with one registration map; that cleanup is resolved, as detailed in the addendum. This is historical evidence, not a current removal candidate.

`scheduledWakeRegistrations` overlaps in fields with `AndroidAppRuleWakeScheduler.registrations`, but the host and scheduler have distinct responsibilities: the host tracks due time for early/failing wake recovery and takes an observation-time token snapshot, while the scheduler owns platform registration. Similar fields do not prove duplicate responsibility, and this investigation found no demonstrated safe state reduction. A typed wake value and atomic conditional-cancel operation on `AppRuleWakeScheduler` remain a review hypothesis, not an established simplification; they could add interface methods and coupling.

Any seam change would need a concrete duplicated invariant, a before/after ownership map, and evidence of a net interface and concurrency-complexity gain before implementation. It must preserve the `runtimeLock` / `wakeSchedulerLock` revalidation order, worker source order per package, generation and runtime revision gates, destroy cancellation, coalesced delivery, and absolute due time. Keep `latestRecheckPlanSourceOrderByPackage` separate from active alarm registration unless a replacement also preserves the cancellation tombstone after a plan is removed. At the pinned snapshot, deferment was appropriate while the parallel scheduler review was active; the later implementation is described in the `0b06bab0` addendum. These earlier line locations are historical, not current implementation instructions.

## 4. Test-only adapters in `src/main`

`FakeWakeScheduler.kt` (155 lines) and `DeterministicForegroundObservationSource.kt` (14 lines) are concrete test adapters compiled from `src/main`. A tracked reference search found their declarations as the only main-source references. Both are consumed by JVM tests and Android instrumentation tests: `FakeWakeScheduler` is used in `FakeWakeSchedulerTest`, `AppRuleBlockerDestroyDrainTest`, scheduler wiring, and multiple instrumentation suites; `DeterministicForegroundObservationSource` is used by both `ForegroundEvidenceContractTest` and its instrumented contract test. Neither belongs in production source.

Move them together into a shared test-only source directory, for example `app/src/sharedTest/java/neth/iecal/curbox/domain/apprules/`, and add that directory to both existing `test` and `androidTest` Java/Kotlin source sets in `app/build.gradle.kts`. Those source sets compile the shared files separately against their respective classpaths; the normal production source set will no longer package them. This uses the current `:app` module and existing dependencies, adds no production dependency and no new module. It is a move, not test deletion. The Gradle source-set configuration has not been built or validated in this investigation.

## 5. Measurement harness and its protocol

The conditional bundle is `AppRuleBlockerFixedFixtureP95MeasurementTest.kt` (629 lines), `FixedFixtureCanonicalData.kt` (191), and `FixedFixtureLedgerCanonicalTest.kt` (51), for about 871 lines at the pinned snapshot. The measurement test (`:158` and `:533`) asserts that it runs in the expected debug app process, generates 20 warmups and 100 allow plus 100 deny samples, writes canonical samples/metadata/digest files, rereads and verifies the byte counts and hashes, calculates a p95 from the 200 accepted rows, and prints the measurement. It does **not** compare the p95 with a performance threshold, so it is an artifact-producing measurement harness rather than a performance regression gate.

Its metadata path accepts instrumentation arguments for device/commit/APK identity but falls back to embedded literals when arguments are absent; it also writes a fixed app-version label that predates the current `app/build.gradle.kts` version. I verified these branches without copying any device identifier or hash into this report. Unless the caller always supplies accurate arguments, collected metadata can silently describe the wrong run. If retained, require the run metadata or populate version/build fields from the actual artifact.

Tracked-source and CI/scripts searches at the pinned snapshot found no consumer of the measurement class or emitted digest files outside the three Android-test files. The only hidden-workspace matches were archived copies of these sources and release documents, not an executable consumer. No tracked connected-instrumentation CI invocation was found. This does not prove there is no external analysis tool outside the repository, so the external consumer question remains unresolved. This ticket does not authorize deleting or isolating the measurement bundle.

At execution HEAD `0b06bab0`, the harness lifecycle and persistence behavior is confirmed by source. It obtains `AppDatabase.getInstance(targetContext)` and injects a `RoomCurrentUseDaySessionRepository` and `RoomUsageResetRepository` into a real `AppRuleBlocker` (`AppRuleBlockerFixedFixtureP95MeasurementTest.kt:179–194`). The production worker's session and statistics path calls that repository, so the harness performs real Room writes (`SerializedDecisionWorker.kt:1253–1271`). The test's cleanup calls pass `PACKAGE_ALLOW` and `PACKAGE_DENY` to `finishOpenSessions`, whose parameter is a `useDayId` (`AppRuleBlockerFixedFixtureP95MeasurementTest.kt:316–318,485–487`; `CurrentUseDaySessionRepository.kt:66`; `RoomCurrentUseDaySessionRepository.kt:148`). The worker derives the real use-day key from the observation timestamp (`SerializedDecisionWorker.kt:624–625`), so those package constants do not identify its rows and the calls do not establish that synthetic rows were cleaned up. The measurement also writes its artifact files under the target app's `filesDir/ticket27/<runId>` and does not remove them.

The test does not call `blocker.onDestroy()` before returning. Its worker is launched in the blocker's scope, and `SerializedDecisionWorker` waits in `for (work in requests)` between requests (`AppRuleBlocker.kt:917`; `SerializedDecisionWorker.kt:316,329`). With no blocker teardown in this test, that idle worker remains suspended for the instrumentation process lifetime, until process shutdown. These persistence and lifecycle findings show that the harness is not isolated from application state. They do not demonstrate cross-test result contamination: no such result contamination was observed or proven.

## 6. High-confidence no-value test/code candidates

These are the four narrow candidates from the earlier test cleanup investigation, rechecked at execution HEAD `0b06bab0`. This report grants no deletion authorization; it records evidence only. All source and test line references in the table below are at that commit.

| Candidate | Current evidence | Classification / confidence | Estimated loss |
|---|---|---|---|
| `GuardianApprovalWorkReceiptTest.kt:70` — `directGrantReceiptRequiresTheExactNonAccumulatedWrite` | The same true/false direct-vs-accumulated checks, including generation, are already in `:31` of `directAndAccumulatedGrantsKeepTheirDifferentMeaningAndGeneration`. A tracked name search finds no other reference. | Delete one duplicate test; high confidence. | One test’s repeated assertions; no unique behavior coverage. |
| `UsageResetCommandPolicyTest.kt:9` and `UsageResetCommandPolicy.kt:6` — `acceptedAt` identity wrapper | The test only asserts `1234L == acceptedAt(1234L)`. Production callers at `AppRuleBlocker.kt:1393` and `AppUsageTracker.kt:327` already calculate one time and pass it through. The same object’s pending-overlap policy and test are used and should remain. | Inline/remove only the identity helper and its test; high confidence. | About 5–8 lines plus the trivial function; no shared-instant behavior is exercised by this test. |
| `AppBlockerServiceTest.kt:24` — appended handled strings | The test manually appends “mindful” and “event-channel” after the cancellation helper returns, then asserts the list. It never dispatches those service features. Keep the real cancellation, isolation, and nonfatal-report assertions at `:11` and `:28`. | Remove the fake wiring assertions or replace with an actual dispatch test; high confidence that the present assertion is not evidence. | Four non-behavioral lines; do not delete the useful cancellation test. |
| `ExampleInstrumentedTest.kt:19` — `useAppContext` | Asserts that `targetContext.packageName` equals `BuildConfig.APPLICATION_ID`. This is the only explicit target-context versus application-ID check found. | Unique package smoke check, not a duplicate no-value assertion. Keep it or replace it with an equivalent assertion in a more useful test; if removed without replacement, record that the smoke check is lost. | Removing it loses the sole explicit target package/application ID assertion; no equivalent assertion remains. |

## Recommended order

1. Keep all race and integration coverage. If code navigation is a real cost, split the five guardian worker tests and centralized fixture/support into same-package `src/androidTest` files.
2. Move the two JVM/Android test adapters from `src/main` to a shared `test` + `androidTest` source directory; verify source-set compilation when implementation is authorized.
3. Treat the first three small cleanup candidates as removable duplicate or non-behavior assertions, after confirming no concurrent edit changed their ranges. Treat the `ExampleInstrumentedTest` package assertion separately: removing it loses the only explicit package smoke check unless an equivalent check is added elsewhere.
4. The write-only pending wake set identified at `2892ab00` was removed by the `0b06bab0` follow-up; do not carry it forward as an open cleanup. Revisit the larger registration bookkeeping only if a concrete duplicated invariant and a net interface/concurrency-complexity gain are demonstrated.
5. Ask whether the fixed-fixture measurement protocol is still used. The in-repository search found no consumer but cannot see an external collector; this report and ticket do not authorize deleting or isolating its bundle.

## Scheduler addendum at execution HEAD `0b06bab0`

The follow-up `0b06bab021fd9f854b1f438b8e4880a0c25491d4` resolves the pinned report's write-only-set candidate. It removes `pendingSchedulerWakePackages` and `pendingSchedulerWakeDueAtByPackage` and replaces them with `pendingSchedulerWakeByPackage: Map<String, ScheduledWakeRegistration>`. Each captured registration carries both its scheduler token and absolute wall-clock deadline (`AppRuleBlocker.kt:399–407` at `0b06bab0`). The observation snapshots these identities, derives its package keys from that map, and clears an entry only when the live map still holds the exact registration it observed (`:1361–1372,2127–2133,2272–2277`). This retains a newer same-package wake that arrives during an older observation.

Current recovery uses both that pending-registration identity and the observation's initial scheduler token snapshot. It considers pending wake packages and explicit `Unknown` candidate packages, subtracts packages resolved as visible or not visible, and schedules a recovery boundary only while the connection and recheck generations still match. The helper installs recovery only when the observation began without a registration and none appeared while it ran; it does not overwrite a registration created during observation or resurrect one that was cancelled meanwhile (`AppRuleBlocker.kt:2192–2269,4164–4187`). The new tests exercise same-deadline replacement retention and recovery that avoids resolved packages (`AppRuleBlockerRecheckTest.kt:3838–4070`). The earlier `pendingSchedulerWakePackages` Set is therefore a resolved historical cleanup item; the broader registration-ledger overlap remains a design hypothesis, not a confirmed simplification.
