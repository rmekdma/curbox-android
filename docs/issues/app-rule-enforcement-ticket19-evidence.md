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

## Framework lifecycle baseline run

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

## External outcome extension run

The user subsequently authorized temporary Debug Curbox rule-data mutation on this test device.
The debug-only provider now adds one UUID-scoped Calculator group and zero-minute, full-day rule
through `DataStoreManager.updateAppRuleSnapshot`; it never calls AppRuleBlocker or a service
event method. The controller waits for the resulting production refresh publication before
launching Calculator. Before mutation it rejects an existing pending APP_RULES change, an active
settings-delay gate, a tamper gate that could defer cleanup, or an existing ticket19 rule. Cleanup
uses a debug-only transaction on DataStoreManager's existing singleton to remove only the matching
UUID rule/group from the latest effective snapshot and from every matching pending APP_RULES
snapshot. It rewrites those pending payloads in the same transaction, retains their unrelated
groups/rules and metadata, and then verifies the UUID absent from effective, pending, and editing
views.

Exact successful controller command:

    & .\app\src\androidTest\ticket19-framework-tracer.ps1 -Serial 'T811MA256GB23418064398'

The qualifying rerun started at 2026-09-08T19:39:58.5420614+09:00 and printed TRACE_COMPLETE at
19:41:21.9400921 with exit code 0. The device started Dozing, so the controller captured that
state, woke and unlocked the display before expecting accessibility events, and restored Dozing
after all accessibility restoration checks. Earlier 16:54 and 16:57 attempts timed out with
zero foreground/evaluator observations while the screen was off; the 17:03 diagnostic run proved
the hypothesis by reaching denial immediately after wake/unlock.

The successful external outcome used UUID 21d74803-b5b5-43ba-8339-52ce11da14c0. After install
publication and work quiesced, the controller returned HOME, verified
the Guardian activity was absent, reset the observer to a zero-count baseline, and armed a
10-second UUID one-shot window. An explicit
`am force-stop com.android.calculator2` followed by
`am start -W -n com.android.calculator2/.Calculator --es ticket19_run_token <UUID>` reported a
COLD, Status-ok launch. In service PID 28721 the debug registry wrapped the current worker's
existing value collaborators and consumed the window on the first accepted DecisionRequest whose
request reason and signal kind were both REAL_EVENT and whose event/evaluation package was
`com.android.calculator2`. Source identity 20 then appeared on exactly one causal request record,
its denied evaluation, its denied DecisionOutcome, and the warning-before-framework-call record.
The registry also counted three total evaluator completions, all denied, zero allowed, and one
warning framework boundary. After that warning, `dumpsys activity activities` reported
`neth.iecal.curbox.debug/neth.iecal.curbox.ui.activity.GuardianApprovalActivity` as top-resumed;
it was absent at baseline. Curbox main PID 28532 was distinct from service PID 28721. The UUID is
therefore an armed-window correlation value rather than a value stamped onto unrelated callbacks.

The active zero-minute rule legitimately retained three scheduled lifecycle callbacks. After
the Guardian activity was closed, removal attempt 1 verified the UUID group/rule absent from the
effective value, pending APP_RULES JSON, and settingsForEditing view. The resulting refresh and
worker work completed, a main-queue boundary was crossed, and scoped quiescence held with those
three lifecycle-owned callbacks unchanged and with denial/warning counts stable. Actual framework
disable then reduced all work counters, the five AppRule registrations, and PID 28721's system
filters to zero. The
same PID rebound with a new service identity and no temporary rule before the barrier and other
lifecycle branches continued. This scopes quiescence to the lifecycle boundary that owns the
long-lived callbacks rather than misclassifying an active scheduled recheck as a completed task.

The run then reproduced the prior barrier completion, narrow AppRule reapply, deterministic
failure transport, and distinct-process checks. Process termination changed PID 28721/token
571fef4e-2ef1-416d-b5c6-ba077c3f20e4 to PID 29834/token
1016d49c-80c9-4980-84f6-e656f97e1d82. Exact-PID, post-cursor log inspection found six Shizuku
leak signature lines, the paired headline/exception lines for three actual framework destroys.
Filters reached zero after each relevant disable or old-process exit, so the classification
remains a teardown anomaly, not stale ownership or delivery.

The run removed the temporary rule, restored SafeInCloud-only/accessibility_enabled=1, never
added Lock Me Out, and restored the initial Dozing state before TRACE_COMPLETE.

The 19:34:25 diagnostic run established a sharper cleanup boundary: the three scheduled callbacks
do not disappear on rule removal alone, although every settings view is already clean. Its first
cleanup ACK incorrectly required every lifecycle work counter to be zero and the run exited 1.
The restoration-grade finally path still verified the UUID absent and restored SafeInCloud-only,
accessibility_enabled=1, and exact Dozing. The qualifying rerun replaces that overbroad condition
with removal refresh/worker completion plus scoped stability, while retaining the subsequent real
framework disable requirement that drains all three callbacks to zero.

The final Standards hardening run used the same command, started at
2026-09-09T11:50:08.6534788+09:00, and printed TRACE_COMPLETE at
11:51:31.1613827 with exit code 0. Install and removal no longer accept an untagged
`runtimePublicationCount` increment. For each mutation the debug registry armed a one-shot,
high-priority receiver, and the controller sent a distinct UUID-tagged framework broadcast. The
one-shot delegated to the existing production refresh receiver, captured the exact source/revision
pair reserved synchronously by that callback, and acknowledged completion only after that revision
reached the production publication seam, refresh/worker work completed, a main-queue boundary was
crossed, and scoped quiescence held.

The install broadcast UUID was `78ded3e6-90aa-4450-bf5e-6e083f56c102`: ActivityManager recorded
exactly one delivery to current service PID 26600, and the registry recorded exactly one reservation
(source identity 15, runtime revision 5), one matching publication, and one completion ACK. The
removal broadcast UUID was `c4cd8375-319c-4fee-89cd-68fbbb5da791`, with the same one-to-one chain
at PID 26600, source identity 35, and runtime revision 7. The Calculator rule UUID was
`1cf7bd6e-353a-412b-b261-97478b97a694`; the real accessibility event again produced three denied
evaluations, zero allows, one warning boundary, and a causal one-shot chain at source identity 22
through the newly top-resumed Guardian activity. Removal attempt 1 verified effective, pending, and
editing absence. The run changed process PID/token from 26600/`0bac4c6f-422f-4e7f-8560-7d9b0ad30066`
to 27823/`f0e3cf88-c9e4-41fc-bcb3-e46e52d9f1b1`, restored SafeInCloud-only with
accessibility_enabled=1, never added Lock Me Out, and restored exact Dozing before TRACE_COMPLETE.

## Remaining required evidence

Trigger 1 also remains unresolved at the required ordering. In production
AppBlockerService.onDestroy calls super.onDestroy and then AppRuleBlocker cleanup as its first
feature cleanup. The final service cannot be subclassed, and the debug component factory has only
an instantiation callback, not a hook inside onDestroy before that first cleanup. A debug-only
fault can be injected into AppRule itself or after AppRule cleanup, but neither proves that an
earlier feature cleanup fault permits AppRule scheduler/receiver/worker cleanup. Adding the
required pre-cleanup hook to production, opening the final service, or directly invoking destroy
would violate the accepted safety/evidence constraints. No fault-ordering claim is made.

Trigger 2's independent scheduler caller remains absent. Trigger 3 now has scoped actual-framework
barrier, post-resume completion, teardown/quiescence, and real external denial evidence. Trigger 4
has scoped receiver/filter/process evidence with no stale delivery, while the Shizuku leak warning
remains an anomaly rather than a reproduced stale owner.

## Decision

**INCONCLUSIVE. Ticket19 remains open and the architecture approval gate remains closed.**
The runs add valid framework lifecycle and external denial evidence, but do not satisfy Trigger 1
fault ordering. They therefore support neither a ticket-level NO-GO nor GO. No lifecycle host,
coordinator, integration, OEM work, or implementation ticket is authorized.

## Verification

- Final compileFullDebugKotlin plus installFullDebug after the mutation-correlation correction:
  BUILD SUCCESSFUL in 21s before the qualifying rerun.
- Final connectedFullDebugAndroidTest filtered to Ticket19ObserverBinderTest: 2/2 passed,
  BUILD SUCCESSFUL in 38s. This includes AIDL access and non-shell rejection for all provider
  entrypoints. On this API 33 device ContentResolver normalizes getType's remote SecurityException
  to null, so the test also invokes getType directly and verifies its SHELL_UID guard.
- testFullDebugUnitTest, assembleFullDebug, assemblePlaystoreDebug, assembleFdroidDebug,
  assembleFullRelease, assemblePlaystoreRelease, and assembleFdroidRelease ran together:
  BUILD SUCCESSFUL in 3m 29s.
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
