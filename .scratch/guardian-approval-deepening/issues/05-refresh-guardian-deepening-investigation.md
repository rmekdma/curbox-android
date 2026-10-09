# 05: Reconcile the guardian deepening report with current scheduler work

**What to build:** Keep the guardian deepening investigation useful for current maintenance by separating the pinned comparison from later scheduler work and recording the review decisions with precise evidence.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Label the comparison from `15164980` through `2892ab00` as a historical pinned snapshot. Preserve the `0b06bab0` scheduler addendum as historical context, read and record the actual HEAD at execution time, remove the stale claim that its committed changes remain uncommitted, and make line references unambiguous about which revision they describe.
- [x] Reconcile scheduler conclusions with current behavior: mark the write-only wake-set cleanup as resolved, describe the current pending-registration identity and recovery behavior, and avoid carrying forward claims that no longer describe the current implementation.
- [x] Preserve distinct guardian launch claim dispositions: missing lookup retains the claim; current settings-read failure releases it and reports retryable failure; policy/window invalidation releases it and requests read-only re-evaluation; deadline expiry releases it and reports timeout; the first authorization's quiet focus/keyguard rejection releases it; the final synchronous `Rejected` retains it; stale identity/package continuations exit without explicitly releasing a claim; launch success releases it and finishes; launch exception retains it; cancellation propagates. Do not imply every state transition automatically clears the claim. Keep worker integration coverage distinct from Activity and workflow unit coverage.
- [x] Record the confirmed measurement-harness lifecycle and persistence findings accurately: real Room writes, package names passed where a use-day key is expected, no blocker teardown, and a suspended worker lasting until process shutdown. State that cross-test result contamination was not demonstrated.
- [x] Reclassify the template instrumentation package assertion accurately. It is the only explicit target-context versus application-ID check; if the test is removed, state that this smoke check is lost and do not claim an equivalent assertion remains.
- [x] Keep the external artifact consumer question unresolved and make clear that this ticket does not authorize deleting or isolating the measurement bundle.
- [x] Update the four small cleanup dispositions to distinguish removable duplicate or non-behavior assertions from the unique package smoke check.
- [x] This documentation-only ticket requires no Gradle build.

## Implementation, review, and validation

- Updated `.scratch/guardian-approval-deepening/code-size-investigation.md`; report commit: `ff1a46f257f742dc8bde65e385a0cb0f90e09747` (`docs: reconcile guardian deepening report`).
- Standards and Spec reviews reported no findings; the parent agreed with both, so no rebuttal or follow-up review was needed.
- Documentation-only validation: `git diff --cached --check` passed. Gradle/build/test suite: N/A; no build was required.
