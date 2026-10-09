# 06: Make the fixed-fixture measurement safe to retain and rerun

**What to build:** Keep the fixed-fixture measurement verifiable across runs while preserving its real Room-backed worker path, canonical artifacts, and unrelated Curbox data.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Preserve the measured path through real Room DAOs and repository processing and the serialized decision worker. Preserve workload and package identities unless a protocol-approved decision changes them. Keep the measurement boundary, sample population and order, clock source, canonical artifact fields, serialization, digests, and p95 calculation unchanged.
- [x] Validate required external run identity before the first fixture event or database mutation. Do not silently substitute embedded identity, commit, or APK values when the runner does not provide them. Obtain application version, application ID, and variant from the actual runtime build. Keep the existing metadata schema and artifact protocol unchanged.
- [x] Isolate fixture-owned state with either targeted cleanup of only records created by this fixture or a demonstrably disposable isolated Room environment that exercises the same measured Room and worker path. Do not require per-run package keys or change workload/package identities without protocol approval. Never use a broad shared use-day cleanup, clear app data, or reset user settings.
- [x] Quiesce the blocker, worker, pending callbacks, and owned effects through existing lifecycle or test seams before restoring owned state or disposing of an isolated environment. Ensure this cleanup runs after success, assertion failure, timeout, abort, and artifact write or verification failure. Cleanup errors must not hide the original failure.
- [x] Seed unrelated database state and confirm it remains unchanged, along with existing settings. After teardown, verify that no owned work or fixture changes remain outside a disposable environment, or that the isolated environment has been disposed.
- [x] Keep cleanup test-local. Do not add a schema change, new production cleanup API, or architecture change without direction; if safe isolation cannot be achieved within that boundary, record the blocker and request direction.
- [x] Run the retained measurement successfully on an available device and exercise the failure cleanup path. Confirm the canonical output remains parseable and its digests match; report any device check that could not run.

## Implementation and verification

- Commit: `750af5fe8118bae8a185885db7fae0905aa5803d` (`test: isolate guardian fixture measurement`). The change is limited to `AppRuleBlockerFixedFixtureP95MeasurementTest.kt`.
- The six runner identity values are required before database setup; runtime build metadata comes from `BuildConfig`. The fixture uses a unique file-backed `AppDatabase`, snapshots settings, seeds and checks an unrelated usage row, drains the blocker, then closes and deletes the database. An injected post-worker failure also forces a cleanup assertion error to verify that the original failure remains primary.
- The measurement ran on the connected iPlay50 device; the instrumentation class reported 3/3 tests passed, including the retained 200-sample measurement and the failure cleanup path. The output JSONL rows, metadata, and digest file parsed successfully, and the reported SHA-256 values matched.
- Android test compilation passed for `fullDebug`, `playstoreDebug`, and `fdroidDebug`.
- `testFullDebugUnitTest --rerun-tasks` passed: 576 tests, 0 failures, 0 errors, 0 skipped (88 XML reports).
- The Gradle `connectedFullDebugAndroidTest` task twice failed before test execution with a host `java.io.IOException: Access is denied`, including with a workspace-local Android user home. The already-built APKs were installed with `adb install -r`, and the class was run directly with `adb shell am instrument`; that run passed without clearing app data.

## Review

- Spec review: no findings.
- Standards initially flagged the literal `Room.databaseBuilder` use. I explained that the ticket explicitly permits a disposable isolated Room environment and requires the same database path. The reviewer withdrew the finding; no review-requested source changes remained.
- Parent accepted the report. An optional reduction of setup duplication in the failure test was deferred to preserve the measurement setup and its separate observation policy.

## Autonomous judgments

- Chose a unique file-backed database built from the production `AppDatabase` class to preserve Room I/O without opening or mutating the user's shared database.
- Derived a stable pseudonymous device ID from the connected device serial and supplied it, along with the actual commit and APK hashes, as external instrumentation arguments.
- Used direct instrumentation execution after the Gradle connected task's host permission failure; both installs used `-r`, with no app-data clearing or settings reset.
