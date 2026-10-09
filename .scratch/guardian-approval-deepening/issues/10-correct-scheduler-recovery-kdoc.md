# 10: Correct the scheduler recovery comment for current behavior

**What to build:** Make the scheduler recovery comment describe the registration behavior implemented after the latest foreground recovery change.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Correct the comment that says recovery can replace the exact registration observed before an observation. Current behavior only installs recovery when no registration existed at the snapshot and no registration exists when recovery is applied.
- [x] Describe how an existing or concurrently published registration is preserved, without implying that the observed registration is replaced.
- [x] Keep the change comment-only. Do not alter scheduling, registration identity, wake delivery, cancellation, or recovery behavior.
- [x] Review the wording against the existing wake-replacement and recovery tests. No new test or Gradle build is required for this documentation-only change.

## Implementation

- Updated the scheduler recovery KDoc in `AppRuleBlocker.kt`; no executable code changed.
- Commits: `ad4a0910` (`Correct scheduler recovery KDoc`) and `77c4537a` (`Clarify tracked wake in recovery KDoc`). The follow-up accepted the Standards review's precision note by naming the package's tracked registration explicitly.
- Reviewed against `visiblePackagePlanIsNotOverwrittenForAnotherUnknownApplicationSlot`, `staleNotVisibleCancellationCannotRemoveReplacementRegistration`, `relativeVisibilityAndSuspendedRecoveryRefreshesExpiredBoundary`, and `productionRecoveryWakeAlarmReceiverAcceptsOnlyCurrentTokenAndWakesOnce`.
- Verification: `git diff --check` and `git show --check` passed. No Gradle build or test run was needed for this comment-only change.
- Reviews: Standards and Spec reviewers reported no remaining findings. The Standards wording suggestion was accepted; no review feedback was rejected.
