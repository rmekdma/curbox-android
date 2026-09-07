# Ticket19 Phase 4 service lifecycle evidence

Status: **provisional and inconclusive; ticket19 remains open.** The GO recorded by `0fd5d473`
was withdrawn after dual review. The architecture approval gate remains closed.

Baseline: `db1066a0`, with ticket18 green and final.

## Rejected probe

The `0fd5d473` probe is not qualifying full-service lifecycle evidence and has been removed.
It instantiated an `AppBlockerService` subclass directly, attached a `Context` through
reflection, and invoked `onServiceConnected()` twice on the same object. Android did not bind,
disconnect, or rebind the accessibility service, and the sequence did not run as a framework
managed `:app_blocker_service` process lifecycle. It therefore cannot be described as an actual
service reconnect and cannot establish old and new service identities.

The probe also had these evidence defects:

- Its receiver list was an append-only registration-attempt log. It did not retain filter and
  active ownership only after successful registration, remove ownership only after successful
  unregister, or deliver a matching broadcast to prove a duplicate callback and external effect.
- It observed repeated non-AppRule registration attempts but did not compare the narrower
  per-feature idempotent setup remedy. Registration attempts alone establish neither trigger 4
  nor lifecycle-host materiality.
- Worker and barrier failures were not transported to the test thread independently of service
  error containment. A timeout or worker exception could be swallowed, so a green assertion did
  not prove that the intended interleaving completed.
- The cleanup fault occurred after AppRuleBlocker cleanup. It only showed that one later receiver
  cleanup was attempted after a `FocusModeBlocker` unregister failure; it did not prove that
  AppRuleBlocker scheduler cancellation survives an earlier cleanup failure.
- Private reflection and direct settings/runtime publication manipulation dominated the fixture.
  That bypassed the real settings and refresh boundary needed for service-level evidence.
- Making `AppBlockerService` open and foreground startup protected/open widened production APIs
  solely for this rejected harness. Those changes have been removed.

No result or log count from the rejected probe is retained as trigger evidence. No qualifying
full-service lifecycle test remains after the probe's deletion. The retained trigger 1 and 3
observations below are limited to the in-process component/manual harness scope and are not
framework service evidence.

## Independent canonical trigger status

The four triggers remain independent and open for the full framework lifecycle:

1. **Cleanup exception bypass:** unverified at the framework-bound service boundary. The
   ticket17/18 in-process component/manual harness retains only feature-level cleanup and
   cancellation observations. It does not establish full-service cleanup ordering. Existing
   service code contains feature cleanup individually, but no deterministic service test injects
   a fault before AppRuleBlocker cleanup and observes its scheduler cancellation plus final
   teardown. The rejected probe's narrower claim was only that a later receiver unregister was
   attempted after a later feature fault.
2. **Second independent scheduler caller duplication regression:** absent in the current
   production tree. AppRuleBlocker remains the only owner of its recheck scheduler and wake
   receiver. No caller or production abstraction was invented.
3. **Post-guard destroy/setup side effect:** not reproduced at the tested AppRuleBlocker
   component/manual harness boundary, where ticket18's deterministic fault/cancellation tests
   remain green. This observation is limited to that blocker boundary. The rejected probe's
   barrier sequence is not retained as evidence because worker or barrier failures could be
   swallowed. The framework-bound service/process lifecycle is still unverified.
4. **Stale service or receiver ownership after reconnect:** unverified. No successful active
   receiver ownership duplication, matching duplicate broadcast callback, stale old/new service
   identity, or duplicate external effect has been observed under a real framework reconnect.

## Decision

There is insufficient evidence for GO or a final NO-GO. Ticket19 remains open, the approval gate
stays closed, and no lifecycle host, coordinator, integration, OEM work, or implementation ticket
is authorized or published.

A lifecycle host cannot be judged materially necessary until a qualifying baseline first proves
a duplicate external effect and compares the narrower per-feature idempotent setup remedy. If the
narrow remedy removes the effect, the decision is NO-GO. GO can be reconsidered only if at least
one canonical trigger remains deterministic and a lifecycle host materially removes it better
than that narrower remedy.

## Exact external prerequisite

The next qualifying run requires an out-of-process framework lifecycle harness on a controlled
Android device or emulator. It must:

1. Enable the manifest-declared accessibility service through Android, verify that it runs in
   `:app_blocker_service`, and drive real framework disconnect/rebind or process restart while
   recording callback order and distinct old/new process and service instance identities.
2. Report receiver ownership over IPC only after successful registration, including receiver
   identity, owner, and `IntentFilter`; remove it only after successful unregister and preserve
   operation ordering.
3. Send a matching broadcast after reconnect and correlate active registrations with per-feature
   callback count and an externally visible publication. Trigger 4 requires an actual duplicate
   callback/effect, not a repeated registration attempt.
4. Expose deterministic barriers whose entered, released, timed-out, and failed states are
   reported to the instrumentation process. Worker throwables must fail the test outside service
   error containment, and the test must prove the worker is blocked before lifecycle mutation.
5. Inject a named cleanup failure before the cleanup whose survival is claimed, automatically
   clear the fault in `finally`, disable the accessibility service, and verify complete receiver,
   worker, notification, and process teardown.
6. Re-run the same observation with the narrow per-feature idempotent setup remedy before any
   lifecycle-host materiality claim.

This prerequisite is test infrastructure, not approval to add production lifecycle APIs. Any
future hook must be test-build constrained, keep a non-bypassable production default, and be
documented before use.

## Fresh framework feasibility run (2026-09-07)

The requested device run was attempted against `T811MA256GB23418064398` (`iPlay50_mini_Pro`,
Android 13/API 33), using the repository's supported task and shell-only observation. No
framework tracer or production visibility seam was retained.

The supported install path did establish a real framework bind once:

```text
$env:JAVA_HOME = C:\Users\DELL\.jdks\jbr-21.0.11
.\gradlew.bat :app:installAndGrantAccessibilityFullDebug
BUILD SUCCESSFUL
```

Immediately after that run, the secure setting contained the Curbox component and
`accessibility_enabled` was `1`. `dumpsys accessibility` reported Curbox in `Bound services`,
and `ps -A` reported `neth.iecal.curbox.debug:app_blocker_service` (PID `27976`).
`dumpsys activity services` identified the same process as the component's
`BIND_ACCESSIBILITY_SERVICE` host and reported `startForegroundCount=1`. `dumpsys activity
broadcasts` showed dynamic `ReceiverList` entries owned by that service PID, including the
`neth.iecal.curbox.refresh.app_rules` filter. A matching shell broadcast returned
`Broadcast completed: result=0` and appeared in broadcast history.

The controlled lifecycle results were not sufficient for ticket19:

- Writing an empty enabled-service list and `accessibility_enabled=0` removed Curbox from
  framework `Bound services`; re-enabling Curbox bound it again, but the service process kept
  the same PID (`27976`). This is not distinct old/new process identity or complete process
  teardown.
- After disabling and running `adb shell am force-stop neth.iecal.curbox.debug`, writing the
  Curbox component back did not rebind. Logcat reported the exact platform failure:
  `ActivityManager: Unable to launch app neth.iecal.curbox.debug/11210 for service Intent {
  cmp=neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService }: process is bad`.
  Package state also reported `stopped=true`.
- A clean debug uninstall/install and another enable attempt reproduced the same failure with
  the new app UID (`11211`): `Unable to launch app ... for service ...: process is bad`.
  During the retry, the device's accessibility manager/other installed software rewrote the
  enabled-service list; Curbox remained only in `Enabled services` while `Bound services`
  contained LockMeOut, with no Curbox service PID. This device behavior prevents a reliable
  automated process-restart/rebind cycle.

The debug/androidTest-only IPC design was also bounded by the platform. The existing test APK
has no endpoint in the target `:app_blocker_service` process. `UiAutomation`/adb can read secure
settings, `dumpsys`, process identity, receiver filters, and broadcast history, but cannot read a
private receiver callback counter, force a worker barrier, transport a worker throwable outside
the service's containment, or observe named cleanup/notification teardown. An
`androidTest`-only provider or service would run in the test APK/process; it cannot become an
IPC observer inside the target's accessibility-service process. Adding a target debug component
and production call sites for those observations would recreate the rejected production
visibility seams and widen the release surface. No such seam was added, and no direct attach,
manual `onServiceConnected`, subclassing, or double-callback evidence was used.

At cleanup, Curbox was removed from the enabled list and had no `:app_blocker_service` PID.
The shell reported only SafeInCloud in the secure list; `pm list packages -u` no longer reported
`com.teqtic.lockmeout`, so Android rejected restoring that originally observed component. This
last device discrepancy cannot be repaired from this workspace without the missing package.

Conclusion: the run remains **inconclusive, gate closed, ticket19 open**. The real framework bind
and same-process disable/enable observation are retained as feasibility evidence only; the
required distinct process reconnect, IPC callback/effect correlation, deterministic barriers,
fault transport, and complete teardown were not demonstrated. No lifecycle host, coordinator,
integration, or OEM work is authorized by this result.

## Retained valid evidence

- No qualifying full-service lifecycle test remains after the rejected probe was removed. The
  following observations remain valid only at their stated component/manual harness boundaries:
- `AppRuleBlockerDestroyFaultRedTest` remains valid component-level fault/cancellation evidence;
  it does not represent a framework service reconnect.
- `AppRuleReceiverLifecycleTest` remains valid for transactional rollback and idempotent cleanup
  of the AppRule receiver helper; it does not measure service-wide active receiver ownership.
- `AppBlockerServiceTest` remains valid for its narrow synchronous cancellation containment
  contract; it does not drive Android service binding.

## Verification of the correction

- `git diff --check`: clean.
- `testFullDebugUnitTest`: Gradle task `UP-TO-DATE`; the report aggregate is 350 tests with
  zero failures, errors, or skips.
- `connectedFullDebugAndroidTest` filtered to
  `neth.iecal.curbox.blockers.AppRuleBlockerDestroyFaultRedTest`: 21/21 passed on
  `iPlay50_mini_Pro - 13`.
- `assembleFullDebug`, `assemblePlaystoreDebug`, and `assembleFdroidDebug`: all passed.
- `AppBlockerService.kt` and `BaseBlockingService.kt` match their exact `0fd5d473^` contents;
  the deleted `AppBlockerServiceLifecycleProbeTest` is not present.
- The build emitted the repository's existing KSP/Kotlin compatibility and deprecation warnings;
  no new failure was reported. No framework-bound lifecycle probe was run or retained.
