# 25: Guardian lifecycle closure

**What to build:** Make Guardian approval lifecycle behavior stable when the same denied app rule is encountered repeatedly or across the completed decision and visibility paths, so the user sees one correct approval flow rather than duplicate or stale approval screens.

**Blocked by:** 21 — Long-boundary foreground decision closure; 22 — Recheck policy closure; 23 — Visibility-window closure; 24 — Callback flush closure

**Status:** done

- [x] Work only the cases assigned to `T25-GUARDIAN-LIFECYCLE` in Ticket 20's frozen inventory. Ticket 20 is the source of truth for the exact names or stable identifiers and count; do not rediscover, duplicate, or reassign cases here.
- [x] Implement the smallest complete change that closes the owned Guardian lifecycle cases while preserving the existing approval and denial behavior.
- [x] Add or update focused tests for the owned repeated-denial, repeated-evaluation, activity-visibility, cleanup, and reconnect behavior covered by the frozen inventory.
- [x] Run the relevant connected and JVM verification and record screen identity, decision count, cleanup, exact owned-case outcome, and every remaining failure classification against Ticket 20.
- [x] Update the canonical evidence and limitation records with the verified owned Guardian lifecycle behavior, inventory identifiers, and remaining gaps.
- [x] Do not invent a new approval policy, timing threshold, device substitution, or architecture change; stop and ask the user if the existing behavior does not specify the outcome.

**Scope:** Keep this as one vertical slice within 150k context. Do not absorb policy, visibility, callback, or fixture work outside the frozen T25 inventory.

## Implementation evidence — 2026-09-10

Ticket 20 assigns exactly one case to T25:
`GuardianApprovalActivityLifecycleTest::repeatedDenialReusesTheVisibleApprovalScreen`.
The historical full-suite observation that first recorded this case remains in the frozen
inventory; it was not relabeled from one intermittent pass. The deterministic gap was the
repeated launch contract: the shared Guardian intent relied only on the manifest's `singleTop`
declaration and the activity had no `onNewIntent` path, so a reused instance could retain stale
denial rows and the test did not verify the refreshed visible content.

The smallest fix adds `FLAG_ACTIVITY_SINGLE_TOP` to the existing `NEW_TASK` intent and handles a
valid `onNewIntent` by updating the activity's intent, replacing the denial rows once, and
preserving the same activity and close lifecycle. Invalid or empty repeated payloads leave the
current approval screen unchanged. No approval policy, timing threshold, architecture, or device
substitution was added.

The TDD regression first failed on the missing explicit reuse flag. After the fix, the focused
connected class on `iPlay50_mini_Pro - 13` / Android 13 passed `3` tests with `3` passed,
`0` failures, `0` errors, and `0` skipped (XML timestamp `2026-09-10T03:08:12`, timezone not
encoded). The owned test observed one `GuardianApprovalActivity` instance after the repeated
request, exactly one approval choice row, and the updated denial reason on that same visible
screen. The same class also passed the existing close-broadcast and background-finish cleanup
tests. Supporting reconnect/no-duplicate Guardian assertions remain in T23's separate ownership
scope and were not reclassified as T25.

The full JVM suite `testFullDebugUnitTest` passed `351/351` with `0` failures, `0` errors, and
`0` skipped. The post-fix full connected suite on the same iPlay50 device reported `92` tests,
`91` passed, `1` failed, `0` errors, and `0` skipped (XML timestamp `2026-09-10T03:07:19`).
The sole remaining failure was `ExampleInstrumentedTest::useAppContext`, classified against
Ticket 20 as the T26 debug application-id fixture. T21, T22, T23, T24, and T25 had no failure
node in that execution. Xiaomi Pad Pro 2025 12.7 Android 15/16 validation remains the final T29
gate.

No child/thread operations were performed, and no code-review or spawning skill was invoked.
