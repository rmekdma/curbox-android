# Ticket19 Phase 4 service lifecycle evidence

Status: **INCONCLUSIVE. Ticket19 remains open and the architecture gate remains closed.**

Baseline: 0433dbbd plus the debug/androidTest-only tracer changes recorded by the follow-up
commit. The withdrawn 0fd5d473 manual attach probe remains rejected: it did not use a
framework-managed accessibility lifecycle, measured append-only attempts, and widened production
test surfaces. None of its results are retained.

## User state and restoration contract

The user removed Lock Me Out because it malfunctioned. That was an external user action, not a
tracer effect. Lock Me Out must never be restored. The run baseline and final verified state were:

    enabled_accessibility_services=com.safeincloud/.autofill.chrome.ChromeAutofillService
    accessibility_enabled=1

The controller rejects any baseline containing Lock Me Out and any baseline missing SafeInCloud
before mutation. Before every settings overwrite it verifies the exact ordered service list and
global flag it owns. It verifies each resulting list, adopts Android's observed boolean after the
list write, then explicitly writes and verifies the target flag. A concurrent list or pre-write
flag change aborts instead of being overwritten. Primary and restoration failures are preserved
together, and TRACE_COMPLETE is printed only after exact restoration.

An intermediate run exposed a device behavior relevant to this contract. At
2026-09-08T11:09:21+09:00, changing the enabled list from SafeInCloud to
SafeInCloud plus Curbox synchronously changed accessibility_enabled from 0 to 1. At
11:19:18, writing an already-equal SafeInCloud-only list left the flag at 0. The initial
implementation incorrectly required the first behavior in both cases and stopped before its
final flag write. The user state was immediately restored and verified as SafeInCloud-only/1.
The final controller owns either boolean only after exact list verification and always converges
to the requested final flag.

The supported installAndGrantAccessibilityFullDebug task is not used as a restoration primitive.
It replaces enabled_accessibility_services with Curbox and does not set accessibility_enabled.

The earlier force-stop attempt remains procedurally invalid evidence. Its ordered commands were:

    adb shell settings put secure accessibility_enabled 0
    adb shell am force-stop neth.iecal.curbox.debug
    adb shell settings put secure enabled_accessibility_services neth.iecal.curbox.debug/neth.iecal.curbox.services.AppBlockerService

The package was stopped=true when ActivityManager reported that it could not launch the service.
No app launch cleared the stopped state before the list write, so that output cannot establish a
platform rebind limitation. The old capture did not retain per-command timestamps and none are
reconstructed. Afterward the user manually changed Accessibility settings and removed Lock Me Out;
all observations in that interval are confounded by the external user action. Curbox cleanup
succeeded, and there is no Lock Me Out restoration obligation.

## Debug-only observation surface

The observer service, provider, component factory, registry, AIDL, and controller exist only in
src/debug or src/androidTest. Android creates the real final AppBlockerService and the debug
component factory observes that instance; the tracer does not subclass, attach, or directly call
onServiceConnected.

The exported AIDL observer service retains the custom signature permission. The shell provider
requires android.permission.DUMP in the debug manifest and independently requires
Binder.getCallingUid() == Process.SHELL_UID in call, query, getType, insert, delete, and update.
It no longer accepts the app UID or a signature-matched UID.

The registry reports the current process token/PID, service generation and identity, five
successful AppRule registrations, 15 service-wide receiver object identities, work counters,
publication/evaluation counters, named failures, and run events. Runtime publication is explicitly
an internal pre-worker signal, not an external allow or denial.

## Final framework run

Device: T811MA256GB23418064398, iPlay50_mini_Pro, Android 13/API 33.

Exact controller command:

    & C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe -NoProfile -File app\src\androidTest\ticket19-framework-tracer.ps1 -Serial T811MA256GB23418064398

The final run started at 2026-09-08T11:21:15.5292639+09:00 and exited 0 at
11:22:46.5563862. Scoped observations:

- Android reported Curbox under bound accessibility services. The initial target PID was 4200,
  process token 7fa2f72c-f672-4b33-bc45-0b780dadaab6, service generation 2, and service identity
  199232946.
- The registry reported 15 nonzero service-wide receiver identities: five AppRule receivers and
  representative receivers owned by FocusMode, ReelBlocker, KeywordBlocker, GrayScale, UiHider,
  NodePicker, ReelsCount, MindfulMessage, and AppUsageTracker.
- ActivityManager reported 16 active filters in PID 4200. The extra process filter is consistent
  with the Shizuku receiver registered outside those 15 feature fields. The count was 0 after
  disable, 16 after same-PID rebind, 16 after narrow AppRule receiver reapply, 0 for the killed
  PID, 16 in the replacement PID, and 0 after final disable. This directly covers the rejected
  15-to-30 duplication concern at the observable process-filter level; no doubling survived.
- Every refresh used a UUID extra and the controller selected the one historical BroadcastRecord
  containing that token. Baseline token 2f98aa47-e01e-41b5-9406-d2e497ac9416 had one Deliver
  +3ms to PID 4200. Same-PID token 1c84202a-f172-49fe-86d7-8e16a5194db3 had one Deliver +4ms.
  Narrow-reapply token af9a5252-dc2b-4414-9b37-6aed62118147 had one Deliver +4ms. Distinct-PID
  token b52e1ee4-7239-4ed0-aa3e-6992659c3cbe had one Deliver +7ms to PID 5468.
- For each refresh, the controller waited until the production callback entered, then waited for
  refresh, notification, callback, usage-reset, recheck, queued-worker, and in-flight-worker
  counters to reach zero. A second quiescence acknowledgement proved the internal publication,
  notification, evaluator, warning-boundary, and failure counts stayed stable. No fixed delay or
  uncorrelated latest-history lookup remains.
- Barrier token 54868a79-568f-4816-b1d6-ca37b1ea9fa4 entered the production refresh callback.
  Framework disable destroyed the AppRule instance and removed active process filters. After
  release, refreshContinuationCompletionCount became 1 only after the resumed callback returned
  through its finally path; all work counters were zero and the second quiescence snapshot kept
  downstream/internal publication, warning, notification, evaluation, and failure counts stable.
- Same-PID enable retained PID/token but created service generation 3, identity 133459050, and new
  service-wide receiver identities. Reapplying only AppRule setupReceivers retained the five Java
  receiver identities, kept 16 system filters, and retained one correlated callback. This is the
  required narrower per-feature idempotent comparison.
- The injected runtime-publication failure crossed IPC as
  runtime_publication_injected/IllegalStateException. Its UUID record had one framework delivery;
  quiescence completed and the failure was reset before lifecycle work.
- Process termination changed PID 4200 to 5468 and token to
  26dab301-4fbc-4a53-92e5-2c08d2da9575 while stopped=false. Android rebound automatically.
  The killed PID had zero active filters and the replacement had 16.
- Final disable established only scoped facts: AppRule destroyed, five AppRule registrations
  inactive, service/protection scopes inactive, process filters zero, and no active
  AppBlockerService ServiceRecord. This is not a claim that every library-owned resource completed
  clean teardown.
- The recorded lifecycle run contained the exact AppBlockerService
  `IntentReceiverLeaked`/`rikka.shizuku.ShizukuProvider$1` signature at the PID 4200 destroy and
  again at PID 5468. Follow-up hardening removed its destructive `logcat -c`. The controller now
  captures the last epoch timestamp before mutation with
  `adb shell logcat -d -v epoch -t 1`, preserves all existing logs, and queries only the new
  interval with `adb shell logcat -d -v epoch -T <captured-cursor>`. It accepts only the full leak
  signature and the two PIDs from that run.
  Because old-PID filters were zero and no stale or duplicate delivery survived reconnect, this
  is classified only as a Shizuku teardown anomaly. No canonical stale-ownership failure was
  reproduced, so no Shizuku/integration remedy or lifecycle host was implemented.
- Exact final restoration was verified before TRACE_COMPLETE:
  SafeInCloud-only and accessibility_enabled=1. Lock Me Out was never added.

## Unresolved required evidence

Real external allow or denial remains unresolved. Two safe actual framework event attempts were
made without changing user rule data: Settings at 11:12:32 and Calculator after HOME at
11:21:28. Calculator produced foreground_evidence=com.android.calculator2 in the real bound
service, proving framework event arrival, but evaluationCount, allowedEvaluationCount,
deniedEvaluationCount, and warningFrameworkBoundaryCount all remained zero for 20 seconds.
Creating a temporary denying or allowing rule would mutate user DataStore/Room state; directly
calling the service or blocker would cease to be framework evidence. Therefore no external
allow/denial claim is made.

Trigger 1 also remains unresolved at the required ordering. In production
AppBlockerService.onDestroy calls super.onDestroy and then AppRuleBlocker cleanup as its first
feature cleanup. The final service cannot be subclassed, and the debug component factory has only
an instantiation callback, not a hook inside onDestroy before that first cleanup. A debug-only
fault can be injected into AppRule itself or after AppRule cleanup, but neither proves that an
earlier feature cleanup fault permits AppRule scheduler/receiver/worker cleanup. Adding the
required pre-cleanup hook to production, opening the final service, or directly invoking destroy
would violate the accepted safety/evidence constraints. No fault-ordering claim is made.

Trigger 2's independent scheduler caller remains absent. Trigger 3 has scoped barrier evidence
with no post-destroy continuation effect, but lacks the required external allow/denial boundary.
Trigger 4 has scoped receiver/filter/process evidence with no stale delivery, while the Shizuku
leak warning remains an anomaly rather than a reproduced stale owner.

## Decision

**INCONCLUSIVE. Ticket19 remains open and the architecture approval gate remains closed.**
The run adds valid framework lifecycle evidence but does not satisfy Trigger 1 fault ordering or
the real external allow/denial prerequisite. It therefore supports neither a ticket-level NO-GO
nor GO. No lifecycle host, coordinator, integration, OEM work, or implementation ticket is
authorized.

## Verification

- compileFullDebugKotlin plus compileFullDebugAndroidTestKotlin: BUILD SUCCESSFUL in 21s.
- Final connectedFullDebugAndroidTest filtered to Ticket19ObserverBinderTest: 2/2 passed,
  BUILD SUCCESSFUL in 24s. This includes AIDL access and non-shell rejection for all provider
  entrypoints. On this API 33 device ContentResolver normalizes getType's remote SecurityException
  to null, so the test also invokes getType directly and verifies its SHELL_UID guard.
- installFullDebug: installed on one physical device, BUILD SUCCESSFUL in 8s before the final run.
- testFullDebugUnitTest, assembleFullDebug, assemblePlaystoreDebug, assembleFdroidDebug,
  assembleFullRelease, assemblePlaystoreRelease, and assembleFdroidRelease ran together:
  BUILD SUCCESSFUL in 2m 44s.
- The Full, Playstore, and F-Droid release merged manifests each had zero matches for the observer
  class, authority, permission, component factory, or AIDL name. aapt2 reported zero such manifest
  matches in all three release APKs. dexdump inspected 2 Full, 3 Playstore, and 2 F-Droid dex files;
  each APK had zero Ticket19 observer/AIDL matches.
- The builds emitted the repository's existing Kotlin/KSP compatibility and JDK 21 source/target 8
  deprecation warnings. No verification failed.
- Follow-up static verification reported `PowerShell parser: OK`, no remaining logcat clear
  command, and two of two exact Shizuku signature samples matched. A read-only device probe captured
  cursor `1788843614.875`; `adb shell logcat -d -v epoch -T 1788843614.875` succeeded and returned
  three lines without clearing the device log buffer.
