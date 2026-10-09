# 08: Give guardian worker integration tests a focused home

**What to build:** Make the guardian worker result, send-failure, and timeout-race checks easy to find while preserving their actual service-to-worker path and shared fixture behavior.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Move the five guardian worker integration tests into a cohesive instrumentation suite. Preserve their assertions for accepted allowed results, evaluation-matched denial rows, failed-send permit cleanup, and both timeout/retry orderings.
- [x] Keep the recording service, request builders, reflection helpers, fixture, broadcast expectations, and effect snapshots centralized once for these tests. Do not create duplicate fixture implementations.
- [x] Preserve the real AppRuleBlocker worker and production result-handler path; do not replace it with a fake action interpreter or coordinator-only test.
- [x] Keep existing test method names and selectors stable where practical. If any selector must change, record the new selector and confirm the old one is no longer referenced.
- [x] Ensure fixture gates are always released and DataStore settings are restored if setup, an assertion, or teardown fails. Do not clear unrelated settings or app data.
- [x] Compile all affected instrumentation source sets and run the focused suite on an available device. Also execute the relevant retained scheduler and visibility tests that consume the extracted shared support; compilation alone is insufficient. Report any device check that could not run.

## Implementation and verification

- Commits: `29f8310d` (split the guardian worker integration suite) and `5f3b88a8` (restore fixture settings after setup failure).
- The original guardian test method names are preserved. The five moved test selectors are now:
  - `neth.iecal.curbox.blockers.AppRuleBlockerGuardianWorkerIntegrationTest#actualGuardianWorkerSendsAllowedOfferForAcceptedRequest`
  - `neth.iecal.curbox.blockers.AppRuleBlockerGuardianWorkerIntegrationTest#actualGuardianWorkerSendsRemainingRowsFromItsEvaluation`
  - `neth.iecal.curbox.blockers.AppRuleBlockerGuardianWorkerIntegrationTest#failedAllowedSendCleansPermitBeforeFailedAndAcceptsNextCheck`
  - `neth.iecal.curbox.blockers.AppRuleBlockerGuardianWorkerIntegrationTest#recoveryAcceptedBeforeTimeoutLockRejectsStaleATimeoutAndKeepsBDeadline`
  - `neth.iecal.curbox.blockers.AppRuleBlockerGuardianWorkerIntegrationTest#timedOutASendMayArriveAfterRetryBWithoutChangingItsDeadline`
- The three retained scheduler and visibility selectors passed:
  - `neth.iecal.curbox.blockers.AppRuleBlockerRecheckTest#oneCoalescedWakeProcessesIndependentDueDeadlinesWithExternalOutcomes`
  - `neth.iecal.curbox.blockers.AppRuleBlockerRecheckTest#productionWakeAlarmReceiverRecomputesFromCurrentWallClock`
  - `neth.iecal.curbox.blockers.AppRuleBlockerRecheckTest#relativeVisibilityAndSuspendedRecoveryRefreshesExpiredBoundary`
- The setup-failure regression selector `neth.iecal.curbox.blockers.AppRuleBlockerGuardianWorkerIntegrationTest#fixtureRestoresSettingsWhenSetupFailsAfterSeeding` passed. Total focused device result: 9/9 on the iPlay50 mini Pro. The test APK was installed with `adb install -r`; app data was not cleared.
- AndroidTest Kotlin compilation succeeded for `full`, `playstore`, and `fdroid`; `:app:assembleFullDebugAndroidTest` succeeded.
- `:app:testFullDebugUnitTest --rerun-tasks` passed 576 tests before the instrumentation-only cleanup follow-up. It was not rerun after that follow-up.
- Both prior review findings were accepted and fixed. Both re-reviewers reported no findings; no findings were rejected.
