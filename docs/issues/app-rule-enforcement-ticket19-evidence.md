# Ticket19 Phase4 service lifecycle evidence

Status: GO at the architecture approval gate. This is evidence and decision work only.

Baseline: `db1066a0`, with ticket18 green and final.

## Scope and tracer

`AppBlockerServiceLifecycleProbeTest` is the smallest attached service-level tracer used for
this decision. It attaches a real `AppBlockerService` instance to a recording `Context`, then
drives the actual `onCreate`, `onServiceConnected`, reconnect, accessibility event, and
`onDestroy` boundaries on the main looper. Setup therefore runs the service's real feature
wiring, including AppRuleBlocker setup and all service feature receiver registration. The
recording context observes the API 33 five-argument `registerReceiver` path as well as the
legacy overloads.

The probe uses deterministic barriers and observers, not stress or timing luck:

- A foreground evidence barrier holds an accessibility event across reconnect. The reconnect
  must advance the lifecycle generation, and the old event must not publish an evaluation or
  warning.
- A notification framework-call barrier holds the real notification path immediately before
  publication. Destroy runs while it is held, then the barrier is released and the probe checks
  that no post-destroy notification publication occurs.
- The recording context fault-injects exactly one receiver unregister failure. The probe checks
  AppRuleBlocker ownership, later feature cleanup, and the final receiver lifecycle state.
- The settings collector is explicitly cancelled after real setup. Deterministic framework facts
  and a queued runtime snapshot then measure denial, allow, activity launch, and notification
  publication without relying on scheduler races.

The probe uses two narrowly scoped production testability seams. `AppBlockerService` is open so
an attached instrumentation subclass can execute the real service lifecycle. The protected open
`BaseBlockingService.startForegroundService` lets that subclass suppress only the framework
foreground token call, which cannot succeed on a manually attached service. Without these seams,
the service crashes in framework attachment before feature setup; a manually created service
cannot obtain the system token needed by `Service.startForeground`. Neither seam changes runtime
behavior or introduces a host, coordinator, integration, or OEM abstraction. The probe's
`startActivity` override records the real denial call while containing the external activity.

## Independent canonical trigger results

1. **Cleanup exception bypass.** The probe injects a failure while unregistering the
   `FocusModeBlocker` receiver. All five AppRuleBlocker receiver unregister attempts occur before
   that failure, and a later `ReelBlocker` receiver unregister is still attempted. Destroy
   completes, the AppRule receiver lifecycle is cleared, and the blocked notification has zero
   post-destroy publications. The cleanup containment trigger was not reproduced.

2. **Second independent scheduler caller duplication regression.** A repository search found
   only AppRuleBlocker's own scheduler state, `scheduleRecheck*` implementation, and its
   `schedulerWakeReceiver` path. No independent production caller of the scheduler exists in
   the current tree. This absence is recorded; no second caller or production abstraction was
   invented.

3. **Post-guard destroy/setup side effect.** The probe holds the notification path after its
   final generation guard, destroys the actual service, and releases the barrier. No notification
   publication occurs afterward. The accessibility event held across reconnect produces no old
   generation evaluation or warning. The current generation produces one denial evaluation and
   one real `startActivity` call, while the subsequent allow evaluation produces no additional
   activity. The post-guard callback, worker, and notification side-effect trigger was not
   reproduced.

4. **Stale service or receiver ownership after reconnect.** The same real service instance is
   reconnected. AppRuleBlocker transactionally unregisters and reclaims its five receivers, but
   non-AppRule feature receiver identities are registered again without an intervening
   service-level unregister boundary. This is deterministic receiver ownership evidence at the
   service boundary. It does not claim that an OEM delivered a duplicate broadcast or that a
   stale service object survived outside this in-process observation.

The attached run logged:

```text
reconnect generation 1->2 registrations 15->30; duplicateNonApp=10; appRuleReclaimed=5
destroy cleanup fault=1; unregisterAttempts=15; laterReceiverAttempted=true; notificationPublications=0
```

## Decision

GO is warranted narrowly because canonical trigger 4 is deterministically reproduced at the
actual service lifecycle boundary, and a lifecycle host would materially remove the asymmetric
ownership boundary by making feature setup, reconnect invalidation, receiver teardown, and
service destroy one owner. Triggers 1 and 3 were exercised and contained; trigger 2 is absent.

This result requires explicit user architecture approval before any lifecycle host or coordinator
implementation is attempted. No lifecycle host, integration work, OEM work, or implementation
ticket was published by this change.

## Verification

All commands used `JAVA_HOME=C:\Users\DELL\.jdks\jbr-21.0.11`.

- `./gradlew.bat connectedFullDebugAndroidTest
  "-Pandroid.testInstrumentationRunnerArguments.class=neth.iecal.curbox.services.AppBlockerServiceLifecycleProbeTest"`:
  2/2 passed on `iPlay50_mini_Pro - 13`, including the final actual activity allow/denial
  assertions.
- `./gradlew.bat connectedFullDebugAndroidTest
  "-Pandroid.testInstrumentationRunnerArguments.class=neth.iecal.curbox.blockers.AppRuleBlockerDestroyFaultRedTest"`:
  21/21 passed on the same device.
- `./gradlew.bat testFullDebugUnitTest`: 350/350 passed.
- `./gradlew.bat assembleFullDebug assemblePlaystoreDebug assembleFdroidDebug`: all three
  succeeded.
- `git diff --check`: clean before commit.

The builds retain existing KSP/JDK source-target and test `AccessibilityEvent` deprecation
warnings; none are failures.
