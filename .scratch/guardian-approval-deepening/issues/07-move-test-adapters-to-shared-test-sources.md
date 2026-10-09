# 07: Move deterministic adapters out of production sources

**What to build:** Keep the deterministic wake scheduler and foreground observation source available to JVM and instrumented tests without including them in production builds.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Inventory tracked references across all source sets before relocation. Place one shared copy of each adapter in a test-only source directory consumed by both the JVM test and Android instrumentation source sets. Do not duplicate either implementation.
- [x] Wire the shared source into both test source sets for full, playstore, and fdroid variants, and remove the adapters from production sources.
- [x] Confirm all existing JVM and instrumentation consumers compile and continue to exercise the same adapter behavior.
- [x] Recheck all source sets for production references and confirm no production variant contains either adapter.
- [x] Run the focused JVM suite and the complete Full debug unit test task; compile Android instrumentation tests for all three flavors; assemble all three debug app flavors.

## Implementation and verification

- Implemented in commit `d3433706078418da836f6ac2765486836fa45e82`. The two adapters were moved as exact renames into `app/src/testShared/java`; both `test` and `androidTest` source sets include that directory. The base-tree inventory found 60 tracked references: two definitions in `main`, 28 JVM test references, and 30 instrumentation test references.
- Focused JVM coverage passed: 46 tests across `FakeWakeSchedulerTest` (7), `ForegroundObservationSourceContractTest` (1), `ForegroundEvidenceContractTest` (29), `AppRuleBlockerWakeSchedulerWiringTest` (5), and `AppRuleBlockerDestroyDrainTest` (4). The full `testFullDebugUnitTest` task passed 576 tests with 0 failures, 0 errors, and 0 skipped.
- Android instrumentation tests compiled for Full, Play Store, and F-Droid. All three debug app flavors assembled. Full, Play Store, and F-Droid release Kotlin compilation also passed.
- Current output inspection found both adapters and the relevant consumers in Full unit-test output and each flavor's AndroidTest output. Neither adapter appears in any of the six production Kotlin outputs or in DEX for any of the three debug APKs.
- Standards and Spec reviews converged with no findings.
