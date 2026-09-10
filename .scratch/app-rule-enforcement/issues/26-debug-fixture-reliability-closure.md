# 26: Debug fixture reliability closure

**What to build:** Make the app rule enforcement debug and connected-test fixtures establish and clean up their required state deterministically, so a failing test represents product behavior rather than leaked fixture state or setup order.

**Blocked by:** 21 — Long-boundary foreground decision closure; 22 — Recheck policy closure; 23 — Visibility-window closure; 24 — Callback flush closure; 25 — Guardian lifecycle closure

**Status:** done

- [x] Work only the cases assigned to `T26-DEBUG-FIXTURE` in Ticket 20's frozen inventory. Ticket 20 is the source of truth for the exact names or stable identifiers and count; do not rediscover, duplicate, or reassign cases here.
- [x] Implement the narrowest fixture setup and cleanup change needed to remove the owned fixture reliability failure without changing production behavior.
- [x] Add or update focused tests proving isolation, repeatability, cleanup, and failure reporting for the affected owned fixture paths.
- [x] Run the relevant connected suite and supporting JVM checks from a clean fixture state, preserving and classifying every remaining product or infrastructure failure against Ticket 20.
- [x] Update the canonical test evidence and limitation records with the reliable owned-fixture result, inventory identifiers, and any remaining suite failures.
- [x] Do not mask assertions, relabel product failures as fixture failures, substitute a device, or make an architecture or product-policy decision.

**Scope:** Keep this as one vertical slice within 150k context. Do not change production behavior or absorb failures outside the frozen T26 inventory.

## Implementation evidence

The frozen T26 identifier was exactly `ExampleInstrumentedTest::useAppContext`. The failure was
the test's hard-coded release application ID (`neth.iecal.curbox`) while every debug variant
correctly uses the Gradle application ID `neth.iecal.curbox.debug`. The fixture now compares the
target context with the variant-generated `BuildConfig.APPLICATION_ID` in
[ExampleInstrumentedTest.kt](../../../app/src/androidTest/java/neth/iecal/curbox/ExampleInstrumentedTest.kt).
No production source, manifest, flavor configuration, expected app identity, policy, threshold,
or architecture was changed.

The test has no mutable fixture state, persistent files, receivers, services, or database writes.
The connected task removed its installed target and instrumentation packages after each run; an
ADB package check before the final suite found no Curbox packages. Three independent focused
runs from that clean state each reported `1` test, `1` passed, `0` failures, `0` errors and
`0` skipped on `iPlay50_mini_Pro - 13` / Android 13. The original red run is retained in the
worker record: `expected neth.iecal.curbox` versus `was neth.iecal.curbox.debug`.

Final verification on the same device and full flavor:

- `testFullDebugUnitTest`: 64 XML suites, 351 tests, 351 passed, 0 failures, 0 errors, 0 skipped.
- `assembleFullDebug`, `assemblePlaystoreDebug`, and `assembleFdroidDebug`: all succeeded.
- `connectedFullDebugAndroidTest`: 97 tests, 96 passed, 1 failure, 0 errors, 0 skipped; XML
  timestamp `2026-09-10T04:38:22`.
- The remaining full-suite node was
  `WarningActivityLifecycleTest::warningIsFinishedAfterItLeavesTheForeground`. It is not one of
  Ticket 20's 24 frozen identifiers and is explicitly left outside T26 ownership; it was not
  relabeled as a fixture failure or used to claim a T26 regression.

All acceptance criteria are satisfied. No child, thread, fork, delegation, resume, message,
spawning operation, or `code-review` skill invocation was performed.
