# 09: Remove four verified low-value test cases

**What to build:** Remove only the four confirmed duplicate, template, or non-behavior assertions while keeping the meaningful regression checks around them intact.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Remove the duplicate direct-grant receipt test while retaining the broader test that already checks direct versus accumulated grant meaning and generation.
- [x] Remove the identity-only accepted-time wrapper and its equality-only test while keeping the usage-reset overlap policy and its behavior test.
- [x] Remove the manually appended handled-string assertions while retaining the real service cancellation, isolation, and nonfatal-report assertions.
- [x] Remove the default package-context template smoke test. Explicitly record that it is the only direct target-context versus application-ID assertion and that no equivalent replacement assertion is being claimed.
- [x] Do not remove or weaken the meaningful behavior assertions associated with these cases. Make no production behavior change.
- [x] Run focused JVM tests, compile the full debug instrumentation suite, and build all three debug flavors. Report any verification that could not run.

## Completion notes

- Implementation commit: `1c8be6db`.
- The focused JVM selection passed 4 tests. The full `testFullDebugUnitTest` suite passed 574 tests with 0 failures, 0 errors, and 0 skipped.
- `assembleFullDebugAndroidTest` compiled and packaged the instrumentation suite. `assembleFullDebug`, `assemblePlaystoreDebug`, and `assembleFdroidDebug` all passed. Device execution was not needed for this test cleanup and was not run.
- `ExampleInstrumentedTest.kt` was the sole direct assertion comparing `InstrumentationRegistry.getInstrumentation().targetContext.packageName` with `BuildConfig.APPLICATION_ID`. Removing it loses that smoke check; no equivalent replacement assertion is claimed.
- Review disposition: Standards review found no issues. The Spec review requested an explicit completion acknowledgment, now recorded here as completion metadata; no source correction was needed. Spec reviewer verification of this note is pending.
