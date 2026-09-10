# 27: Fixed synthetic fixture callback-to-evaluation-ready or callback-to-decision-publication-entry p95

**What to build:** First propose a reproducible protocol for measuring a fixed synthetic
single-window fixture path. After the user selects boundary A or B, the objective/result is
named either callback-to-evaluation-ready p95 or callback-to-decision-publication-entry p95. The word
representative refers only to the approved fixed synthetic fixture path; it never means
production Android-window or OEM representativeness. Only after explicit user approval may the
approved protocol be run and its p95 recorded. The measurement is evidence only and must not
become a product requirement without a separate user decision.

**Blocked by:** 21 — Long-boundary foreground decision closure; 22 — Recheck policy closure; 23 — Visibility-window closure; 24 — Callback flush closure; 25 — Guardian lifecycle closure; 26 — Debug fixture reliability closure

**Status:** awaiting-user-approval

## Pre-approval protocol proposal — revision T27-P6 (2026-09-10)

**Proposal status:** awaiting explicit user approval.

This revision is a protocol proposal only. No instrumentation path was run or verified, no
measurement samples were generated or collected, and no p95 was calculated. The remaining
`WarningActivityLifecycleTest::warningIsFinishedAfterItLeavesTheForeground` failure remains
an unassigned preflight observation. A clean preflight reproduction must record whether it is
causally relevant to this fixed harness path and ask the user whether to create a separate
triage ticket. Its existence alone does not block Ticket 27 and this protocol does not absorb
or fix it.

### Boundary choice remains user-owned

The run must choose exactly one of these two end boundaries; this proposal does not choose
between them:

- **A — callback-to-evaluation-ready:** `callbackStart` to the entry of the existing
  `SerializedDecisionWorker` `onEvaluation` seam for the matching request. This is the point
  after visible-session reconciliation, persistence completion/read ordering, and
  `AppRuleEnforcement.check()` have produced an `AppRulesEvaluation`, but before recheck-plan
  publication and Handler/WarningActivity/Guardian effects.
- **B — callback-to-decision-publication-entry:** `callbackStart` to a timestamp taken at entry to the
  existing `DecisionOutcomeSink.publish(DecisionOutcome)` seam for the matching request, after
  the `DecisionOutcome` object has already been constructed. This timestamp does not include the
  sink body or any later Handler/WarningActivity/Guardian effects. If selected, the metric name
  is exactly **fixed synthetic callback-to-decision-publication-entry p95**. B is the provisional
  recommendation because it includes construction of the final outcome while retaining a precise
  pre-sink boundary, but it remains an explicit user choice and is not selected by this proposal.

There is a useful but different outer boundary: full `AppBlockerService.onAccessibilityEvent`
entry to return includes the preceding `AppUsageTracker` fan-out and other service callback
work. It is not selected for this app-rule metric; it may be a separate service-health metric
only after a separate decision.

The objective and result name must follow the selected boundary exactly: `T27-A fixed synthetic
callback-to-evaluation-ready p95` for A or `T27-B fixed synthetic
callback-to-decision-publication-entry p95` for B.
Neither result may be described as production-representative, OEM-representative, or a product
target. It is representative only of the approved synthetic fixture, fixed rule state, and
single-window conditions below.

For either A or B:

- `callbackStart` is the monotonic timestamp immediately before the existing
  `AppRuleBlocker.doAppRuleCheck(event)` action is invoked at the synchronous app-rule service
  boundary. It excludes the preceding `AppUsageTracker` work.
- `callbackReturn` is the timestamp in the action boundary's `finally` path immediately after
  `doAppRuleCheck(event)` exits, whether it returns normally or throws. Every ledger row has a
  `callbackExitKind` field whose enum values are exactly `NORMAL`, `CANCELLATION`, and
  `EXCEPTION`. `NORMAL` is recorded when the action returns; `CANCELLATION` when it exits by
  `CancellationException`; and `EXCEPTION` for any other thrown failure. If the callback has
  not exited when `D` expires, `callbackExitKind` remains null in that row and the terminal
  state must be `TIMEOUT_MISSING_CALLBACK_RETURN` or `TIMEOUT_MISSING_BOTH`; null is not a
  fourth enum value. `CANCELLATION` and `EXCEPTION` are always exclusions, while `NORMAL`
  may still become a valid sample or a later exclusion depending on the selected boundary and
  quiescence gate.
- `selectedEnd` is the A or B timestamp selected by the user. It is correlated through the
  request's existing `SourceOrderIdentity`; no new decision or persistence call is made.
- Retain signed diagnostics: `callbackReturn - callbackStart`,
  `selectedEnd - callbackStart`, and `selectedEnd - callbackReturn`. The last value may be
  negative when the worker reaches A or B before the synchronous callback returns. Never clamp
  or reorder it. A negative `selectedEnd - callbackStart`, an impossible duplicate boundary,
  or a clock-order failure is an explicit exclusion, not a repaired sample.
- A valid sample requires both `callbackReturn` and the selected end boundary. The next
  stimulus is gated until both have been observed, even when `selectedEnd` precedes
  `callbackReturn`.

One sample is one accepted `DecisionRequest` with `reason == REAL_EVENT`, correlated by its
existing `SourceOrderIdentity`, that produces exactly one evaluable package decision and
reaches the selected end boundary. It is not one row per log line, retry, recheck, package
UI effect, or warning.

### Clock and passive correlation

- Every measured boundary and diagnostic timestamp uses Android's
  `SystemClock.elapsedRealtimeNanos()`. Store every such value as a raw integer nanosecond
  field; never convert it to milliseconds before retention or subtract wall-clock time. Wall
  clock values may be retained separately for human-readable run metadata only.
- The diagnostic adapter correlates callback-local timestamps with the request's existing
  `SourceOrderIdentity`, then correlates that identity with the selected A or B seam. It does
  not add timing fields to the decision contract and does not make a second evaluator or
  persistence call.
- Observation must be passive: no blocking I/O, sleeps, latches, measurement locks, scheduler
  posts, queue changes, policy branches, or UI interception on the callback or worker path.
  Required attempt disposition is retained by the lossless ledger described below.

### Proposed isolated instrumentation topology — explicit approval required

Use one instrumentation-only entry point to be added after approval:
`app/src/androidTest/java/neth/iecal/curbox/blockers/AppRuleBlockerFixedFixtureP95MeasurementTest.kt`,
test method `measureFixedSyntheticFixtureP95()`. Its private `FixedFixtureHarness` owns one
manually constructed `AppRuleBlocker`, the production `SerializedDecisionWorker`, production
`AppRuleEnforcement`, production Room-backed current-use-day/reset repositories, a
`FixedFixtureMeasurementService`, the fixed snapshot providers, and the lossless ledger. It
uses the deterministic construction/configuration seams already exercised by the neighboring
`AppRuleBlocker` instrumentation tests. No exported component, benchmark process, live
`AppBlockerService`, or second evaluator is introduced.

`FixedFixtureHarness.create()` attaches `FixedFixtureMeasurementService` to the instrumentation
target context, constructs `AppRuleBlocker` directly, injects
`RoomCurrentUseDaySessionRepository`, `RoomUsageResetRepository`, and
`AppRuleEnforcement` from `AppDatabase.getInstance(targetContext)`, fixes the launchable-package,
window/root, screen, keyguard, clock, scheduler, and snapshot providers, and marks only the
test-owned blocker lifecycle ready. It deliberately does not call production `setup()` because
that would start DataStore collection, notification ticks, visible-app refresh, and reconnect
effects; it also never calls `setupReceivers()`. As in the existing direct callback tests, the
first accepted preflight request reaches `ensureDecisionWorker`, which constructs the production
`SerializedDecisionWorker`; the harness does not construct a substitute worker. After approval,
the only new observation hooks are identity-bearing passive callbacks at `onEvaluation` entry
and as the first statement of `DecisionOutcomeSink.publish`, after its `DecisionOutcome`
argument exists. They may write only raw primitive fields into the preallocated ledger.

The instrumentation runs in the target APK's main app process. Before allocating the harness,
it asserts `Application.getProcessName()` equals the host-pinned target application id and does
not end in `:app_blocker_service` or `:crash_handler`. Device serial/model/API/fingerprint
selection is performed by the host controller, not inferred or verified inside the app. The
instrumentation receives the host-selected raw serial and pseudonym as runner arguments solely
for restricted metadata. It never instantiates, starts, binds, reconnects, or
registers receivers for `AppBlockerService`; Debug Curbox's accessibility service is disabled
for the entire run. Other device accessibility services may remain installed, but they have no
reference to this private blocker. Therefore Android framework accessibility callbacks and
unrelated accessibility traffic have no call path into the measured object.

The sole stimulus entry is `FixedFixtureHarness.dispatch(fixture)`. It creates one synthetic
`TYPE_WINDOW_STATE_CHANGED` event for the approved fixture and uses
`Instrumentation.runOnMainSync` to preallocate its attempt slot, capture `callbackStartNs`, and
directly invoke `appRuleBlocker.doAppRuleCheck(event)` inside `try/finally`; the `finally` captures
`callbackReturnNs`, classifies `callbackExitKind`, and recycles the event. `callbackStart`
therefore still means the synthetic `AppRuleBlocker.doAppRuleCheck` callback boundary, not
instrumentation setup or an outer framework callback. No other method may dispatch an event.
The harness assigns a fresh attempt token before the call and requires every accepted
`SourceOrderIdentity`, selected boundary, recheck plan, and effect to map to that active token.
An unsolicited identity, an event count different from the direct-dispatch count, or any seam
observation without an active token records `ABORT_UNEXPECTED_INGRESS`; this is the runtime
proof complementing the topology's absence of a framework ingress path.

The approved run starts from a clean uninstall of both the FullDebug target package and its
instrumentation package, followed by installation of the two pinned APKs and an empty app-data
state. Preflight must assert the host-pinned device/build identity and in-app process, target accessibility service
disabled and not running, no `AppBlockerService` service-process instance, an empty app-rule
session/reset store, no pending target recheck, exactly the fixed synthetic snapshot, one
application window/root, zero unexpected ingress, and the expected allow/deny result from an
unmeasured fixture sanity phase. Preflight is not a measurement sample. It also records the
unassigned WarningActivity observation described above; a clean reproduction is assessed for
causal relevance and raised to the user for a separate triage-ticket decision, but does not
automatically block this isolated run.

#### Focused WarningActivity preflight and mandatory reset

Before launching `AppRuleBlockerFixedFixtureP95MeasurementTest`, the host runs exactly the
single existing Warning test against the selected device from the pinned source checkout:

```powershell
$env:JAVA_HOME = 'C:\Users\DELL\.jdks\jbr-21.0.11'
$env:ANDROID_SERIAL = 'T811MA256GB23418064398'
.\gradlew.bat connectedFullDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=neth.iecal.curbox.ui.activity.WarningActivityLifecycleTest#warningIsFinishedAfterItLeavesTheForeground"
```

This invocation is a preflight diagnostic only: it does not start the Ticket 27 harness,
allocate its ledger, or collect any latency/p95 sample. Record only pass/fail and the focused
test output. Whether it passes or fails, remove `neth.iecal.curbox.debug` and
`neth.iecal.curbox.debug.test` from the selected device, then reinstall the already pinned
target and instrumentation APK bytes before any Ticket 27 preflight/harness work. Verify empty
app data again after reinstall; output APKs incidentally produced by the focused invocation do
not replace the pinned run inputs.

If the focused failure reproduces, complete that fresh reinstall/reset and then pause before
Ticket 27 instrumentation. Assess whether the failure can causally affect the isolated direct
stimulus, ask the user to own or create a separate triage ticket, and continue only after that
ownership assessment. Recurrence alone is not an automatic Ticket 27 block. If it passes,
continue from the mandatory fresh reinstall/reset. In neither branch may the Warning preflight
produce or be counted as a Ticket 27 sample.

This topology is the provisional recommendation, not an adopted design. The user must
explicitly approve or reject it before the harness or its observation seams are implemented.

### Normal post-sample quiescence gate

The selected boundary ends the measured duration. Normal post-sample quiescence is a separate
gate outside that duration and must complete before the next stimulus. A valid attempt cannot
open the next-stimulus gate until all of the following are true:

1. The synchronous callback has returned, including its final exit classification.
2. The selected A or B boundary has been reached.
3. The worker request and its `SourceOrderIdentity` are fully published and completed; no
   worker-owned work for that identity remains in flight.
4. Required persistence commit and read work for that identity is complete.
5. Every legitimate use-day-reset recheck or Handler effect carrying that attempt's
   `SourceOrderIdentity` has completed or has been removed by the identity-scoped cleanup below;
   no effect for that identity remains pending.
6. Any Guardian/Warning test UI or recorded launch caused by the fixture is deterministically
   closed/cleared, and the synthetic fixture state is restored to its pre-stimulus state.
7. No refresh, reconnect, lifecycle-generation change, or worker replacement occurred during
   the attempt.

Record `quiescenceStartNs` exactly once at the instant both `callbackReturn` and the selected
A/B boundary are present. If the selected boundary arrived first, wait for `callbackReturn`
and take `quiescenceStartNs` when the second signal arrives; do not start `R` at the earlier
boundary. Record `quiescenceEndNs` when this gate closes, using the same raw integer
nanosecond clock. These timestamps and the quiescence duration are retained for diagnosis but
are outside the measured callback-to-A/B duration. The normal recovery deadline is the interval
`[quiescenceStartNs, quiescenceStartNs + R]`. If the gate is not quiescent when that interval
expires, classify the row as `EXCLUDED_POST_SAMPLE_NOT_QUIESCENT`; if the approved exclusion
cap or abort rule is reached, abort the run. Do not dispatch another stimulus while the gate
is unresolved.

#### Identity-scoped recheck, UI, and fixture cleanup

Cleanup begins only after `quiescenceStartNs`, so none of it is part of the measured interval.
The harness-owned scheduler records each legitimate use-day-reset recheck registration as
`(SourceOrderIdentity, Runnable, registration kind)` through the existing recheck-plan and
post/remove seams. After the worker has finished publishing the selected attempt, cleanup
removes only registrations whose originating identity equals that attempt's identity, using
the exact retained `Runnable`; it then waits for any already-running callback with that same
identity to finish. It must not use a global `Handler.removeCallbacksAndMessages`, clear a
different attempt's registration, destroy/recreate the blocker, refresh rules, reconnect the
service, replace the worker, increment lifecycle/recheck generation, or publish a new runtime
revision. A registration without a provable originating identity is an instrumentation failure,
not permission for broad cleanup.

`FixedFixtureMeasurementService` records Guardian/Warning launch intents and prevents them from
escaping the test-owned effect boundary. After approval, add exactly one narrow seam next to
`guardianReceiver` in
`app/src/main/java/neth/iecal/curbox/blockers/AppRuleBlocker.kt`:
`@VisibleForTesting internal fun closeGuardianForInstrumentation(packageName: String): Boolean`.
The receiver's existing package-scoped OPENED/CLOSED state mutation is first extracted without
semantic change into a private `applyGuardianLifecycleTransition(action, packageName)` helper;
both `guardianReceiver.onReceive` and the new seam invoke that same helper. The seam invokes only
the CLOSED transition for the supplied synthetic package and, under `runtimeLock`, returns true
only when `activeGuardianPackage == null` and `lastShownAt == 0L`. It is called only after
`quiescenceStartNs`, is never called by production code, sends no refresh/reconnect, and changes
no rule, worker, lifecycle, or runtime revision. If another package is active or either invariant
is false, cleanup fails rather than clearing broader state.

If an instrumentation-owned Guardian or Warning activity was nevertheless launched during
preflight or an attempt, the harness closes its tracked `ActivityScenario` and waits until it is
destroyed before applying/verifying the package-scoped Guardian transition. It never uses
Back/Home or a package-wide force-stop as per-attempt cleanup. The next-stimulus gate cannot
reopen until the synthetic package's cleanup seam returned true, direct state observation again
confirms `activeGuardianPackage == null` and `lastShownAt == 0L`, and no test-owned Warning UI is
visible. The attempt's synthetic foreground session,
usage/reset rows, captured launch intents, and one-window/root provider are then restored to the
same approved fixture baseline in a transaction/drain step; the fixed rule snapshot and accepted
runtime revision are not reloaded or changed. The harness verifies zero open session/reset rows,
zero same-identity pending effects, no visible test-owned Guardian/Warning activity, and the
unchanged lifecycle generation/runtime revision before opening the next-stimulus gate. This
preserves the next sample's initial state without a lifecycle or runtime refresh.

Failure to remove or drain a same-identity recheck, close tracked test UI, restore fixture rows,
or prove the unchanged generation/revision by `quiescenceStartNs + R` classifies the attempt as
`EXCLUDED_POST_SAMPLE_NOT_QUIESCENT`. The harness retries that same fixture only if the exclusion
is recoverable and the approved exclusion cap has not been reached; otherwise it aborts with the
specific cleanup/restoration reason and reports no p95.

### Terminal handling, recovery, and abort choices

Every attempted stimulus receives a preallocated ledger slot before dispatch. The attempt
state is one of `STARTED`, `CALLBACK_RETURNED`, `END_OBSERVED`, `VALID_SAMPLE`,
`EXCLUDED_<reason>`, `TIMEOUT_MISSING_CALLBACK_RETURN`, `TIMEOUT_MISSING_SELECTED_END`,
`TIMEOUT_MISSING_BOTH`, `RECOVERY_NOT_QUIESCENT`, or an explicit `ABORT_<reason>` state.
There is no silent pending state at run completion.

For an eligible request, the per-attempt observation deadline `D` starts at `callbackStart`
and ends when both `callbackReturn` and the selected end have arrived. If `D` expires before
both signals are present, classify the row immediately at `D` with exactly which boundary is
missing and abort the run as `ABORT_OBSERVATION_DEADLINE`; `quiescenceStartNs` does not exist
and `R` must not be started or inferred. This prevents a missing boundary from creating a
circular quiescence condition. The permitted callback and A/B hooks cannot prove that a request
was rejected before construction, so this protocol defines no separate no-request exclusion and
adds no submission hook. In particular, a `NORMAL` callback return without the selected boundary at
`D` is observably `TIMEOUT_MISSING_SELECTED_END` followed by
`ABORT_OBSERVATION_DEADLINE`, regardless of its unobserved internal cause. It is never counted
as an exclusion eligible for retry, and the run has no p95. Likewise, a missing callback return
or both signals missing uses the corresponding observable timeout state and aborts.

After a terminal exclusion that has both required boundary signals, the harness enters normal
post-sample recovery and sends no new stimulus while the `R` interval is open. It must confirm
all of the following before reopening the gate:

1. The current attempt ledger slot is terminal and no measurement correlation entry for its
   `SourceOrderIdentity` remains open.
2. The service lifecycle generation and worker instance are unchanged, unless the attempt is
   terminally classified as lifecycle invalidation.
3. No fixture stimulus is in flight and any synthetic follow-up created by the attempt is
   either absent or recorded as cancelled/excluded.
4. The callback/selected-end gate is closed for the old attempt; a new stimulus cannot reuse
   its identity or ledger slot.

The user must choose both a terminal observation deadline `D` and a recovery/quiescence
deadline `R`. `D` bounds the pre-quiescence observation phase. `R` starts only at the
unambiguous `quiescenceStartNs` defined above and bounds normal post-sample recovery. These
are measurement-run bounds only, not product thresholds:

| Option | `D` per attempt | `R` after `quiescenceStartNs` | Tradeoff |
| --- | ---: | ---: | --- |
| T1 | 2 seconds | 2 seconds | Tighter run bound; more likely to classify slow debug/DB scheduling as excluded. |
| T2 (provisional recommendation) | 5 seconds | 5 seconds | Still bounded while allowing a full-debug worker/persistence tail to settle; does not reuse a product budget. |
| T3 | User-supplied values | User-supplied values | No numeric value is chosen here; the run cannot start until both values are recorded. |

If normal recovery is not quiescent by `quiescenceStartNs + R`, classify the attempt as
`EXCLUDED_POST_SAMPLE_NOT_QUIESCENT`; it does not become a valid sample. If the exclusion cap
or abort rule is reached, abort the run with `ABORT_RECOVERY_NOT_QUIESCENT`. A normal valid
attempt never opens the next-stimulus gate before `quiescenceEndNs`. A pre-quiescence missing
boundary is handled at `D` as `ABORT_OBSERVATION_DEADLINE` and never uses `R`.

The user must also choose the maximum number of excluded attempts across warm-up and
measurement:

| Option | Maximum excluded attempts | Abort rule |
| --- | ---: | --- |
| E0 | 0 | Abort at the first exclusion or timeout. |
| E5 (provisional recommendation) | 5 | Abort before dispatching another stimulus when the fifth exclusion is terminal. |
| E20 | 20, which is 10% of the requested 200 measured samples | Abort before dispatching another stimulus at the cap. |

The provisional recommendation is T2 + E5, but it is not adopted without explicit approval.
Any abort, ledger-capacity failure, instrumentation error, lifecycle invalidation without
recovery, or build/device mismatch produces no p95. Partial raw rows and the abort reason are
retained for diagnosis only.

### Concrete execution population proposal

The host run controller selects the device with literal selector
`adb -s T811MA256GB23418064398` and maps it to committed pseudonym `T27_DEVICE_01`. Before any
install or preflight, the host runs `adb -s T811MA256GB23418064398 get-serialno` and exact
`adb -s T811MA256GB23418064398 shell getprop` queries for `ro.product.model`,
`ro.build.version.release`, `ro.build.version.sdk`, and `ro.build.fingerprint`. It requires the
serial to equal the selector, model `iPlay50_mini_Pro`, Android 13/API 33, and fingerprint
`Alldocube/iPlay50_mini_Pro/iPlay50_mini_Pro:13/TP1A.220624.014/1699256002:user/release-keys`,
plus the `FullDebug` application id `neth.iecal.curbox.debug`. Any mismatch aborts before install;
the run must not silently substitute another device or build.

The controller uses the same literal selector for every later adb operation. After the focused
Warning preflight it performs the mandatory clean install using the pinned input paths:

```powershell
adb -s T811MA256GB23418064398 uninstall neth.iecal.curbox.debug
adb -s T811MA256GB23418064398 uninstall neth.iecal.curbox.debug.test
adb -s T811MA256GB23418064398 install --no-streaming $pinnedTargetApkPath
adb -s T811MA256GB23418064398 install --no-streaming $pinnedInstrumentationApkPath
```

An `Unknown package` result is acceptable only for the two uninstall commands and is recorded;
any install failure aborts. The controller then verifies both installed package identities and
empty app data before deciding whether the Warning result permits continuation.

The host passes the already selected identity to the approved instrumentation invocation as
runner arguments, using `-e t27RawDeviceSerial T811MA256GB23418064398` and
`-e t27PseudonymousDeviceId T27_DEVICE_01`. Instrumentation records both in restricted raw
`metadata.json` but performs no independent serial selection. The external/committed manifest
contains only `T27_DEVICE_01`; the raw serial is never copied into it.

```powershell
adb -s T811MA256GB23418064398 shell am instrument -w -r `
    -e class neth.iecal.curbox.blockers.AppRuleBlockerFixedFixtureP95MeasurementTest#measureFixedSyntheticFixtureP95 `
    -e t27RawDeviceSerial T811MA256GB23418064398 `
    -e t27PseudonymousDeviceId T27_DEVICE_01 `
    neth.iecal.curbox.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Xiaomi Pad Pro 2025 12.7 on Android 15/16 is not substituted here and remains the final
Ticket 29 validation gate.

The user must approve the immutable run-input identity policy. Immediately before installation,
pin: the source commit containing the measured implementation, the harness commit, the protocol
revision, exact app version/application id/variant, target FullDebug APK SHA-256, and
instrumentation APK SHA-256. The installed APK bytes must hash to those values before preflight
and again when copied into host evidence. A change to any pinned source, harness, protocol, app
identity, or APK bytes invalidates the run and requires a clean restart. Evidence-only or
documentation-only commits made after sampling do not invalidate the run because they are not
run inputs; their parentage and purpose are recorded in the external evidence manifest. The
exact values are metadata, not a pass/fail target.

Use only synthetic fixture identifiers:

| Fixture label | Synthetic package identifier | Fixed rule state and expected decision |
| --- | --- | --- |
| `T27_ALLOW` | `com.curbox.t27.synthetic.allow` | Not selected by any active app rule; expected `isAllowed == true` with no denying rule. |
| `T27_DENY` | `com.curbox.t27.synthetic.deny` | Selected by exactly one active rule `T27_SYNTHETIC_DENY_RULE` through group `T27_SYNTHETIC_DENY_GROUP`; weekdays `(0..6)`, `startMinute = 0`, `endMinute = 0` (the existing full-day representation), `allowedMinutes = 0`, no usage/contributor condition, guardian grant, skip, or pending override; expected `isAllowed == false` with exactly one denying rule. |

The fixed snapshot contains no other app-rule targets. Before warm-up, both fixture packages
have zero open foreground sessions, no pending usage reset, no pending refresh, and no pending
synthetic recheck. The snapshot and fixture state do not change during a run.

Each stimulus is a synthetic `TYPE_WINDOW_STATE_CHANGED` event whose package is the fixture
identifier. The existing one-window fixture seam supplies exactly one application window and
one active root with the same fixture package, `hasApplicationWindow == true`,
`hasUnknownApplicationWindow == false`, no split-screen second package, no essential overlay,
`screenInteractive == true`, and `keyguardLocked == false`. No real package or real rule name
is opened, launched, or written to evidence. This synthetic single-window event and root/window
setup is explicitly measurement scope; it is not evidence of production Android-window or OEM
window-selection representativeness and requires explicit user approval.

The exact population mix is another user choice:

| Option | Warm-up order | Measured order | Population |
| --- | --- | --- | --- |
| M1 (provisional recommendation) | Collect 20 **valid** alternating rows: `T27_ALLOW, T27_DENY` repeated until each scheduled position is valid. | Collect exactly 100 **valid** allow rows and 100 **valid** deny rows in alternating order. | Fixed 50/50 valid population; primary p95 pools all 200 valid measured rows. |
| M2 | `T27_ALLOW` repeated 20 times | `T27_ALLOW` repeated 200 times | 200 allowed only. |
| M3 | `T27_DENY` repeated 20 times | `T27_DENY` repeated 200 times | 200 denied only. |

The provisional recommendation is M1 because it exercises both evaluator outcomes without
random stimulus order, but no mix is selected until the user approves it. Under M1, an exclusion
does not consume a warm-up or measured population slot. After successful recovery, retry the
same fixture until that slot produces a valid row; advance the allow/deny alternation only after
a valid sample. Stop only after 20 valid warm-up rows and then exactly 100 valid allow plus 100
valid deny measured rows, unless an approved abort rule fires. The primary nearest-rank p95 pools
that fixed 50/50 population; per-outcome diagnostics may be retained but do not replace it. The
next stimulus is never dispatched until the current attempt has reached its valid terminal gate
or its fully recovered exclusion terminal. No artificial sleep is inserted between stimuli.

### Inclusion and exclusion rules

Valid rows must use `REAL_EVENT`, an accepted worker submission, exactly one evaluable fixture
package, the fixed one-window facts above, and the selected end boundary. Both allowed and
denied results are retained when selected by M1; outcome and denying-rule count are metadata,
not filters.

Exclude and count separately only states exposed by the permitted callback/A/B hooks: a
non-`REAL_EVENT` `observationKind`; a stale, overlap, cancellation, generation-invalidation, or
recoverable outcome explicitly delivered at those hooks; a multi-package or unknown-root
outcome; a clock-order failure; a post-boundary recovery failure; or an instrumentation/ledger
failure. An ignored, invalid, not-ready, or pre-request internal cause is not guessed from the
absence of an end signal; a `NORMAL` callback with no selected end follows the mandatory
missing-end abort at `D`. No duration is removed just because it is large; there is no outlier
cutoff. A row that cannot be assigned an observable terminal disposition aborts the run instead
of disappearing.

### Lossless attempt disposition and passive observation

The required disposition record is not sent through a bounded observer buffer. Before each
stimulus, allocate one fixed-size `AttemptLedger` record from a preallocated array sized for
`20 warm-up + 200 measured + the approved maximum excluded attempts`. Callback and selected-end
hooks update only primitive fields and an atomic terminal state in that record; they do not do
file I/O, wait, sleep, enqueue work, acquire a measurement lock, call the evaluator, or change
the decision request.

The complete row ledger retains, for every warm-up, measured, and excluded attempt: `runId`,
attempt ordinal, sample ordinal when applicable, source-order identity when available,
`observationKind`, lifecycle generation, accepted runtime revision, `callbackExitKind`,
package decision count, allow/deny result, denying-rule count, commit status, publication
status, every boundary timestamp (`callbackStartNs`, `callbackReturnNs`, `selectedEndNs`,
`quiescenceStartNs`, and `quiescenceEndNs`) as raw integer nanoseconds, the signed durations,
boundary-presence bits, terminal state, exclusion reason, and synthetic fixture label. After the
ledger and run metadata are closed, instrumentation serializes each payload exactly once into
the canonical bytes defined below. The host never serializes or reserializes either payload. If
a ledger slot cannot be allocated or updated, record
`ABORT_LEDGER_CAPACITY` or `ABORT_LEDGER_WRITE_FAILURE` in the run manifest and stop before
dispatching another stimulus. Thus every dispatched attempt has one disposition or the run is
explicitly incomplete; there is no observer-drop count that can contradict the one-row rule.

### Aggregation

For a completed run, sort the valid selected durations, retained as raw integer nanoseconds,
in ascending order as `x[1] <= ... <= x[n]`. Use the nearest-rank method:
`rank = ceil(0.95 * n)` with one-based indexing, and `p95 = x[rank]`. With the proposed 200
valid measured rows, this is the 190th sorted row (zero-based index 189). Do not interpolate,
trim, pool runs, or infer any threshold, target timing, completion guarantee, or implementation
response. The result label is exactly the selected fixed-fixture metric name from the boundary
section: `T27-A fixed synthetic callback-to-evaluation-ready p95` or `T27-B fixed synthetic
callback-to-decision-publication-entry p95`. An aborted or incomplete run has no p95.

### Evidence, privacy, integrity, and deletion

- After the final ledger state is closed, instrumentation creates the canonical
  `samples.jsonl` byte array and canonical `metadata.json` byte array exactly once. It computes
  byte length and SHA-256 directly over each array, writes each array unchanged with an
  app-private `FileOutputStream`, flushes/syncs/closes it, then rereads the staged file as bytes
  to require the same length and SHA-256. It emits those device-side values in a separate
  app-private canonical `device-digests.json` with exact key order `samplesJsonlBytes`,
  `samplesJsonlSha256`, `metadataJsonBytes`, `metadataJsonSha256`, using lowercase hexadecimal
  hashes; neither canonical payload is regenerated. Serialization or
  staged-file mismatch aborts evidence finalization and reports no p95.
- Keep one immutable `samples.jsonl` row for every warm-up and measured attempt, including
  excluded and timeout rows. Use only `T27_ALLOW`/`T27_DENY` and other synthetic identifiers;
  do not retain real package names, real rule names, or user data. Serialize each ledger row as
  one canonical JSON object encoded as UTF-8 without BOM. The key order is exactly:
  `runId`, `attemptOrdinal`, `sampleOrdinal`, `phase`, `fixtureLabel`,
  `sourceOrderIdentity`, `observationKind`, `lifecycleGeneration`,
  `acceptedRuntimeRevision`, `callbackExitKind`, `packageDecisionCount`, `decision`,
  `denyingRuleCount`, `commitStatus`, `publicationStatus`, `callbackStartNs`,
  `callbackReturnNs`, `selectedEndNs`, `quiescenceStartNs`, `quiescenceEndNs`,
  `callbackDurationNs`, `selectedDurationNs`, `selectedEndMinusCallbackReturnNs`,
  `quiescenceDurationNs`, `callbackReturnPresent`, `selectedEndPresent`, `terminalState`,
  `exclusionReason`. Use no insignificant whitespace, JSON escaping
  per RFC 8259, base-10 integers without leading zeroes, lowercase `true`/`false`/`null`, and one
  LF byte (`0x0A`) after every object including the final row. Row order is ascending attempt
  ordinal. No CRLF normalization or post-copy reserialization is allowed.
- Retain a separate canonical `metadata.json` payload, also UTF-8 without BOM and serialized
  once as one object with no insignificant whitespace and one final LF byte. Its key order is
  exactly: `runId`, `startedAtUtc`, `endedAtUtc`, `rawDeviceSerial`, `pseudonymousDeviceId`,
  `sourceCommit`, `harnessCommit`, `protocolRevision`, `appVersion`, `applicationId`, `variant`,
  `targetApkSha256`, `instrumentationApkSha256`, `buildFingerprint`, `deviceModel`, `androidVersion`,
  `apiLevel`, `boundary`, `observationDeadlineNs`, `recoveryDeadlineNs`, `exclusionCap`,
  `populationOption`, `clockSource`, `stimulusOrder`, `lifecycleGeneration`, `rootWindowConditions`,
  `attemptTotals`, `terminalTotals`, `exclusionTotals`. It contains no hash of itself and no
  `samples.jsonl` hash.
- After the run closes, create a separate canonical `evidence-manifest.json`, using the same
  one-object UTF-8/no-BOM/no-whitespace/final-LF rules, that hashes the
  exact `samples.jsonl` bytes and exact `metadata.json` payload bytes with SHA-256. The manifest
  records keys in this exact order: `manifestSchemaVersion`, `canonicalizationVersion`, `runId`,
  `pseudonymousDeviceId`, `sourceCommit`, `harnessCommit`, `protocolRevision`, `appVersion`,
  `applicationId`, `variant`, `targetApkFile`, `targetApkBytes`, `targetApkSha256`,
  `instrumentationApkFile`, `instrumentationApkBytes`, `instrumentationApkSha256`,
  `samplesJsonlBytes`, `samplesJsonlSha256`, `metadataJsonBytes`, `metadataJsonSha256`,
  `deviceStagingDeleted`, `evidenceCommitParent`, `notes`. Metadata never self-hashes. The
  committed form contains only the run-stable pseudonymous device id whose exact-serial mapping
  is stored under restricted custody. The exact serial remains only in restricted raw
  `metadata.json` and is never committed.
- Stage both canonical payloads and `device-digests.json` only in
  `files/ticket27/<run-id>/` in the target app's private directory. The host performs the exact
  binary-safe transfer below, independently computes each copied file's byte length and SHA-256,
  and compares the two payloads with the values in `device-digests.json`. It never parses,
  normalizes, redirects as text, or reserializes `samples.jsonl` or `metadata.json`.

The approved PowerShell 7 host controller uses `ProcessStartInfo.ArgumentList` and copies the
native stdout `BaseStream`; it must not use PowerShell `>`/`Out-File` or `adb shell cat`:

```powershell
$adbExe = (Get-Command adb.exe -ErrorAction Stop).Source
$deviceSerial = 'T811MA256GB23418064398'
$applicationId = 'neth.iecal.curbox.debug'
$runId = '<approved-run-id>'
$evidenceDir = "C:\Users\DELL\StudioProjects\curbox-android\.scratch\app-rule-enforcement\evidence\ticket27\$runId"
[void](New-Item -ItemType Directory -Path $evidenceDir -ErrorAction Stop)

function Copy-AppPrivateEvidence([string]$name) {
    $destination = Join-Path $evidenceDir $name
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adbExe
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @(
        '-s', $deviceSerial, 'exec-out', 'run-as', $applicationId,
        'cat', "files/ticket27/$runId/$name"
    )) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($start)
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $output = [System.IO.File]::Create($destination)
    try {
        $process.StandardOutput.BaseStream.CopyTo($output)
    } finally {
        $output.Dispose()
    }
    $process.WaitForExit()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw "adb copy failed for $name (exit $($process.ExitCode)): $stderr"
    }
}

Copy-AppPrivateEvidence 'samples.jsonl'
Copy-AppPrivateEvidence 'metadata.json'
Copy-AppPrivateEvidence 'device-digests.json'

$deviceDigests = Get-Content -Raw -LiteralPath (Join-Path $evidenceDir 'device-digests.json') |
    ConvertFrom-Json
$checks = @(
    @('samples.jsonl', 'samplesJsonlBytes', 'samplesJsonlSha256'),
    @('metadata.json', 'metadataJsonBytes', 'metadataJsonSha256')
)
foreach ($check in $checks) {
    $path = Join-Path $evidenceDir $check[0]
    $hostLength = (Get-Item -LiteralPath $path).Length
    $hostSha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $deviceLength = [int64]$deviceDigests.($check[1])
    $deviceSha256 = [string]$deviceDigests.($check[2])
    if ($hostLength -ne $deviceLength -or $hostSha256 -cne $deviceSha256) {
        throw "device/host evidence mismatch for $($check[0])"
    }
}
```

The controller also copies the already pinned target and instrumentation APK input files with
`[System.IO.File]::Copy(source, destination, $false)`, names them
`target-full-debug.apk` and `instrumentation-full-debug-androidTest.apk` in the same new evidence
directory, and records `Get-Item.Length` plus lowercase `Get-FileHash -Algorithm SHA256` for each.
Those hashes must equal the hashes pinned before installation. This is a byte-for-byte host file
copy, not extraction from or reserialization by the app.

```powershell
$apkCopies = @(
    @($pinnedTargetApkPath, 'target-full-debug.apk', $pinnedTargetApkSha256),
    @($pinnedInstrumentationApkPath, 'instrumentation-full-debug-androidTest.apk', $pinnedInstrumentationApkSha256)
)
foreach ($copy in $apkCopies) {
    $destination = Join-Path $evidenceDir $copy[1]
    [System.IO.File]::Copy([string]$copy[0], $destination, $false)
    $length = (Get-Item -LiteralPath $destination).Length
    $sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sha256 -cne ([string]$copy[2]).ToLowerInvariant()) {
        throw "pinned APK hash mismatch for $($copy[1])"
    }
    # Record $length and $sha256 in the external evidence manifest.
}
```

Only after the two canonical host payloads match their device lengths/hashes and both retained
APK copies match their pinned hashes does the controller delete the three exact device staging files with
`adb -s T811MA256GB23418064398 shell run-as neth.iecal.curbox.debug rm -f` followed by each exact
`files/ticket27/<run-id>/<name>` path. It then verifies those three paths are absent and records
`deviceStagingDeleted: true` when serializing the final external manifest. A mismatch or failed absence check preserves available evidence,
aborts finalization, and forbids reporting p95.

- Keep the host raw files in the restricted workspace through review and ticket closure.
  Delete them only after explicit user instruction or explicit ticket-archive approval, and
  record the deletion in the ticket. No encryption is required while the files remain in the
  restricted workspace; moving them outside it requires a separate approval and retention
  decision.
- Anchor evidence integrity by committing the pseudonymized external
  `evidence-manifest.json`. The manifest commit is the trusted reference; raw files and raw
  metadata are not committed while their restricted workspace copies are retained. Later
  evidence/docs commits do not alter the pinned run inputs.

### Explicit approval points

Before any measurement work, the user must explicitly choose:

1. Boundary **A** (`fixed synthetic callback-to-evaluation-ready`) or boundary **B**
   (`fixed synthetic callback-to-decision-publication-entry`). **B is recommended but not
   selected.** The outer full-service boundary is not selected.
2. Terminal/recovery deadlines: T1, T2, or user-supplied `D` and `R`.
3. Exclusion/abort cap: E0, E5, or E20.
4. Population mix: M1, M2, or M3.
5. The proposed isolated `AppRuleBlockerFixedFixtureP95MeasurementTest` topology, main target
   process, direct-only stimulus path, ingress proof, focused Warning preflight plus mandatory
   clean reinstall/reset sequence,
   and identity-scoped recheck/UI/fixture cleanup. **This topology is recommended but not
   selected.**
6. The pinned serial/device/build identity and immutable run-input policy: source, harness,
   protocol revision, app identity, and both APK hashes are fixed before installation; later
   evidence/docs-only commits do not invalidate the run.
7. The synthetic single-window fixture scope and its explicit limitation that it does not
   establish production Android-window or OEM representativeness.
8. Lossless preallocated ledger retention, canonical byte format, pseudonymous committed
   manifest, restricted raw serial/metadata, verified transfer, device deletion, and host
   retention/deletion policy.

The currently recommended approval bundle is **B + T2 + E5 + M1 + the proposed isolated
topology + the pinned identity policy + the stated retention policy**. Every element remains
user-owned and may not be inferred from this recommendation.

No measurement, instrumentation verification, sample collection, p95 calculation, or
threshold decision may begin before those choices are approved.

- [x] Before collecting any measurement samples, write the revised protocol proposal above. No instrumentation was run and no samples were collected while writing it.
- [x] **MUST STOP** immediately after recording the protocol proposal and request explicit user approval. This revision did not verify the measurement path, run tests/Gradle, collect samples, or report a p95.
- [ ] After approval, verify that the approved measurement path can observe the selected boundaries without changing decision behavior solely to obtain a number, then run only the approved protocol and record the observed p95, exact conditions, population, retained evidence, and known limitations in the canonical documentation.
- [ ] Do not invent a pass or fail threshold, target timing, completion guarantee, or implementation response; if a threshold decision is needed, stop and ask the user for it.

**Scope:** Keep this as one vertical measurement slice within 150k context. Do not implement latency behavior or select a threshold.
