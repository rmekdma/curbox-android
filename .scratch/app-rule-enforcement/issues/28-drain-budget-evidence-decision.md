# 28: Drain budget evidence and decision

**What to build:** Establish whether the current service stop and drain behavior needs a production numeric drain budget or completion guarantee, and produce the evidence and decision question required before changing that behavior.

**Blocked by:** 20 — Canonical connected-suite failure inventory and documentation baseline reconciliation

**Status:** done

- [x] Collect and document evidence for the existing stop, cancellation, drain, and quiescence behavior against the already approved lifecycle invariants.
- [x] Determine from evidence whether a production numeric drain budget or completion guarantee is actually required, without selecting one.
- [x] If such a decision is required, stop implementation and ask the user exactly: “Should production adopt a numeric drain budget or completion guarantee, and what exact value and completion semantics should it have?”
- [x] Do not introduce a numeric budget, deadline implementation, architecture change, or altered stop behavior before the user's decision; record the decision or deferral in the canonical documentation.
- [x] Preserve the existing recovery-only behavior and report all evidence, test results, and remaining uncertainty.

## User decision — 2026-09-13

The user explicitly decided: **"RecoveryOnlyStop 유지"** (retain `RecoveryOnlyStop`).
- Production continues to use `RecoveryOnlyStop` without an operational numeric drain budget (`TotalDrainDeadline`) or completion guarantee (`DeadlineDrainStop`).
- Immediate lifecycle invalidation, worker/job/handler cancellation, and durable reconnect recovery via `recoverOpenSessions()` remain the canonical production behavior.
- No production source changes or numeric thresholds were introduced.

## Implementation evidence — 2026-09-13

### 1. Existing Stop, Cancellation, Drain, and Quiescence Evidence
- **Approved Architecture Invariants (D6, D5)**:
  - D6 explicitly mandates `EXPLICIT_MEASUREMENT_DEFERRAL`: production uses `RecoveryOnlyStop` without a `TotalDrainDeadline` or completion guarantee.
  - Production teardown (`AppRuleBlocker.onDestroy()` via `destroyInternal(totalDrainBudgetMs = null)`) immediately invalidates lifecycle generation, marks `setupReady = false`, sets `destroyed = true`, clears worker instance tokens, cancels scheduled jobs (`settingsJob`, `notificationTickJob`, `liveNotificationJob`), cancels scheduled rechecks, purges handler callbacks, cancels coroutine `scope`, unregisters `receiverLifecycle`, and invokes `decisionWorker?.stop(RecoveryOnlyStop(...))`.
  - In `SerializedDecisionWorker.stop(request: RecoveryOnlyStop)`, the worker immediately sets `accepting = false`, updates `currentLifecycleGeneration` to invalidate in-flight publications, clears wall-clock boundaries, snapshots remaining work, cancels `workerJob`, and returns `DrainResult.RecoveryOnly(remainingWork = hasWork, durableRecoveryRequired = hasWork)`. It claims neither completion nor timeout.
  - In-flight durable state is preserved and recovered exclusively upon reconnect via `AppUsageTracker.setup()` -> `recoverOpenSessions()`, maintaining zero post-destroy side-effects across all external boundaries (evaluator, warning, notification, handler, reset broadcasts).
  - Teardown ordering in production `AppBlockerService.onDestroy()` executes `cleanupFeature { appRuleBlocker.onDestroy() }` first among all features, fully isolated by `cleanupFeature` error containment boundaries.
- **Candidate Measurement Seams**:
  - `AppRuleBlocker.onDestroyForMeasurement(totalDrainBudgetMs: Long)` and `SerializedDecisionWorker.stop(request: DeadlineDrainStop)` provide deterministic measurement across one shared absolute `TotalDrainDeadline`. These seams remain strictly isolated to testing/instrumentation and are not wired to production execution paths.
- **Verification Evidence**:
  - Deterministic lifecycle contract tests in `AppRuleBlockerDestroyFaultRedTest` (including `ticket15MeasuresBoundedDestroyAndReconnectsOnlyTheLatestGeneration` and `sameHostSetupDestroySetupPublishesOnlyLatestGeneration`) pass at `21/21`.
  - Worker stop semantics tests in `SerializedDecisionWorkerTest` (including `deadlineStopTimesOutFinalDecisionAndQueuedRefreshForRecoveryOnly`, `deadlineStopDrainsFinalDecisionAndQueuedRefreshBeforeAbsoluteDeadline`, and `repeatedGenerationReplacementPublishesOnlyTheLatestWorkerGeneration`) pass at `23/23`.
  - Full JVM unit test suite (`testFullDebugUnitTest`): `390/390` tests passed across 68 XML test suites with 0 failures, 0 errors, and 0 skipped.
  - All three build variants (`assembleFullDebug`, `assemblePlaystoreDebug`, `assembleFdroidDebug`) build successfully.

### 2. Determination on Whether Production Numeric Drain Budget / Guarantee Is Required
- **Technical Sufficiency vs. Policy Gate**:
  - **Technically / Functionally**: A production numeric drain budget (`TotalDrainDeadline`) or completion guarantee (`DeadlineDrainStop`) is **not required** for correct app-rule enforcement, cancellation safety, or system stability. The existing `RecoveryOnlyStop` architecture fully satisfies all approved invariants: immediate generation invalidation, zero post-destroy side effects, suppressed stale evaluations/publications, and reconnect-based durable recovery.
  - **Policy / Architectural Mandate**: Approved architecture decision D6 (`EXPLICIT_MEASUREMENT_DEFERRAL`) explicitly mandates that before adopting any numeric budget or transitioning production to `DeadlineDrainStop`, an explicit product decision must be authorized by the user rather than inferred by engineering.
- **Decision Question**:
  - As required by the ticket specification, because transitioning from `RecoveryOnlyStop` to an operational `DeadlineDrainStop` with numeric thresholds and completion semantics requires product authorization:
    > “Should production adopt a numeric drain budget or completion guarantee, and what exact value and completion semantics should it have?”

### 3. Preservation of Existing Recovery-Only Behavior
- No numeric budget, deadline implementation, architecture change, or altered stop behavior was introduced in production code.
- Production continues to use `RecoveryOnlyStop`.
- All deterministic tests, typechecks, and multi-flavor builds remain green.
- Xiaomi Pad Pro 2025 12.7 Android 15/16 physical OEM deep-sleep validation remains reserved for Ticket 29.
