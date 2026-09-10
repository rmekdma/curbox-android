# 27: Callback and decision p95 measurement

**What to build:** First propose a reproducible protocol for measuring the representative callback-to-decision p95 for the completed app rule path. Only after explicit user approval may the approved protocol be run and its p95 recorded. The measurement is evidence only and must not become a product requirement without a separate user decision.

**Blocked by:** 21 — Long-boundary foreground decision closure; 22 — Recheck policy closure; 23 — Visibility-window closure; 24 — Callback flush closure; 25 — Guardian lifecycle closure; 26 — Debug fixture reliability closure

**Status:** awaiting-user-approval

## Pre-approval protocol proposal — revision 2026-09-10

**Proposal status:** awaiting explicit user approval.

This revision is a protocol proposal only. No instrumentation path was run or verified, no
measurement samples were generated or collected, and no p95 was calculated. The remaining
`WarningActivityLifecycleTest::warningIsFinishedAfterItLeavesTheForeground` failure is
outside Ticket 20's frozen inventory and remains unassigned pending a separate parent or
user ownership decision.

### Boundary choice remains user-owned

The run must choose exactly one of these two end boundaries; this proposal does not choose
between them:

- **A — callback-to-evaluation-ready:** `callbackStart` to the entry of the existing
  `SerializedDecisionWorker` `onEvaluation` seam for the matching request. This is the point
  after visible-session reconciliation, persistence completion/read ordering, and
  `AppRuleEnforcement.check()` have produced an `AppRulesEvaluation`, but before recheck-plan
  publication and Handler/WarningActivity/Guardian effects.
- **B — callback-to-published-decision:** `callbackStart` to a timestamp taken at entry to the
  existing `DecisionOutcomeSink.publish(DecisionOutcome)` seam for the matching request, after
  the `DecisionOutcome` object has already been constructed. This timestamp does not include the
  sink body or any later Handler/WarningActivity/Guardian effects. The metric must be named
  callback-to-published-decision if B is selected.

There is a useful but different outer boundary: full `AppBlockerService.onAccessibilityEvent`
entry to return includes the preceding `AppUsageTracker` fan-out and other service callback
work. It is not selected for this app-rule metric; it may be a separate service-health metric
only after a separate decision.

For either A or B:

- `callbackStart` is the monotonic timestamp immediately before the existing
  `AppRuleBlocker.doAppRuleCheck(event)` action is invoked at the synchronous app-rule service
  boundary. It excludes the preceding `AppUsageTracker` work.
- `callbackReturn` is the timestamp in the action boundary's `finally` path immediately after
  `doAppRuleCheck(event)` exits, whether it returns normally or throws. The row records the
  exit kind (`NORMAL`, `CANCELLATION`, or `EXCEPTION`); an abnormal exit cannot be a valid
  latency sample.
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

### Normal post-sample quiescence gate

The selected boundary ends the measured duration. Normal post-sample quiescence is a separate
gate outside that duration and must complete before the next stimulus. A valid attempt cannot
open the next-stimulus gate until all of the following are true:

1. The synchronous callback has returned, including its final exit classification.
2. The selected A or B boundary has been reached.
3. The worker request and its `SourceOrderIdentity` are fully published and completed; no
   worker-owned work for that identity remains in flight.
4. Required persistence commit and read work for that identity is complete.
5. No recheck or Handler effect for that identity remains pending.
6. Any Warning/Guardian test UI caused by the fixture is deterministically closed, and the
   synthetic fixture state is restored to its pre-stimulus state.
7. No refresh, reconnect, lifecycle-generation change, or worker replacement occurred during
   the attempt.

Record `quiescenceStartNs` after the selected boundary and `quiescenceEndNs` when this gate
closes, using the same raw integer nanosecond clock. These timestamps and the quiescence
duration are retained for diagnosis but are outside the measured callback-to-A/B duration. If
the gate cannot reach quiescence within the approved recovery deadline `R`, classify the row as
`EXCLUDED_POST_SAMPLE_NOT_QUIESCENT`; if the approved exclusion cap or abort rule is reached,
abort the run. Do not dispatch another stimulus while the gate is unresolved.

### Terminal handling, recovery, and abort choices

Every attempted stimulus receives a preallocated ledger slot before dispatch. The attempt
state is one of `STARTED`, `CALLBACK_RETURNED`, `END_OBSERVED`, `VALID_SAMPLE`,
`EXCLUDED_<reason>`, `TIMEOUT_MISSING_CALLBACK_RETURN`, `TIMEOUT_MISSING_SELECTED_END`,
`TIMEOUT_MISSING_BOTH`, `RECOVERY_NOT_QUIESCENT`, or an explicit `ABORT_<reason>` state.
There is no silent pending state at run completion.

For an eligible request, the per-attempt observation deadline starts at `callbackStart` and
ends when both `callbackReturn` and the selected end have arrived. If the deadline expires,
the row records exactly which boundary is missing. For a request rejected before a
`DecisionRequest` exists, `callbackReturn` plus `EXCLUDED_NO_REQUEST` is the terminal
disposition; no end boundary is expected and no next stimulus is sent until that disposition
is closed. For an accepted request that cannot produce the selected end, the row remains a
timeout/exclusion and is never counted as a sample.

After any exclusion or timeout, the harness enters recovery and sends no new stimulus. It
must observe the late callback return and selected end when they eventually arrive, or record
their explicit absence, then confirm all of the following before reopening the gate:

1. The current attempt ledger slot is terminal and no measurement correlation entry for its
   `SourceOrderIdentity` remains open.
2. The service lifecycle generation and worker instance are unchanged, unless the attempt is
   terminally classified as lifecycle invalidation.
3. No fixture stimulus is in flight and any synthetic follow-up created by the attempt is
   either absent or recorded as cancelled/excluded.
4. The callback/selected-end gate is closed for the old attempt; a new stimulus cannot reuse
   its identity or ledger slot.

The user must choose both a terminal observation deadline `D` and a recovery/quiescence
deadline `R`. These are measurement-run bounds only, not product thresholds:

| Option | `D` per attempt | `R` after timeout/exclusion | Tradeoff |
| --- | ---: | ---: | --- |
| T1 | 2 seconds | 2 seconds | Tighter run bound; more likely to classify slow debug/DB scheduling as excluded. |
| T2 (provisional recommendation) | 5 seconds | 5 seconds | Still bounded while allowing a full-debug worker/persistence tail to settle; does not reuse a product budget. |
| T3 | User-supplied values | User-supplied values | No numeric value is chosen here; the run cannot start until both values are recorded. |

`R` starts when the attempt first becomes a timeout/exclusion. If recovery is not quiescent by
`R`, the run aborts with `ABORT_RECOVERY_NOT_QUIESCENT`; it does not continue to collect
samples. A normal valid attempt never waits for `R`: its gate opens only after both required
boundaries and the normal quiescence check are complete.

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

The proposed execution identity is pinned to serial
`T811MA256GB23418064398`, model `iPlay50_mini_Pro`, Android 13/API 33, build fingerprint
`Alldocube/iPlay50_mini_Pro/iPlay50_mini_Pro:13/TP1A.220624.014/1699256002:user/release-keys`,
and the `FullDebug` flavor. At run start, assert all of these values. Any mismatch aborts
before the first stimulus; the run must not silently substitute another device or build.
Xiaomi Pad Pro 2025 12.7 on Android 15/16 is not substituted here and remains the final
Ticket 29 validation gate.

The user must approve the implementation identity policy: the exact app version, source
commit, and measurement-harness commit captured immediately before samples are the final
measurement implementation HEAD. If any of those code or harness inputs changes after capture,
the run is invalid and must be restarted; no later code change may be treated as the same run.
The exact values are metadata, not a pass/fail target.

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
| M1 (provisional recommendation) | `T27_ALLOW, T27_DENY` repeated 10 times | `T27_ALLOW, T27_DENY` repeated 100 times | 100 allowed and 100 denied, fixed alternating order. |
| M2 | `T27_ALLOW` repeated 20 times | `T27_ALLOW` repeated 200 times | 200 allowed only. |
| M3 | `T27_DENY` repeated 20 times | `T27_DENY` repeated 200 times | 200 denied only. |

The provisional recommendation is M1 because it exercises both evaluator outcomes without
random stimulus order, but no mix is selected until the user approves it. The next stimulus is
never dispatched until the current attempt has reached its valid terminal gate or its fully
recovered exclusion terminal. No artificial sleep is inserted between stimuli.

### Inclusion and exclusion rules

Valid rows must use `REAL_EVENT`, an accepted worker submission, exactly one evaluable fixture
package, the fixed one-window facts above, and the selected end boundary. Both allowed and
denied results are retained when selected by M1; outcome and denying-rule count are metadata,
not filters.

Exclude and count separately: invalid or ignored events; not-ready, stale, or overlapping
submissions; synthetic rechecks; refresh/reconnect/screen lifecycle observations;
cancellation or generation invalidation; recoverable outcomes without an evaluation;
multi-package or unknown-root outcomes; no-request exits; clock-order failures; terminal
timeouts; recovery failures; and instrumentation/ledger failures. No duration is removed just
because it is large; there is no outlier cutoff. A row that cannot be assigned one of these
terminal dispositions aborts the run instead of disappearing.

### Lossless attempt disposition and passive observation

The required disposition record is not sent through a bounded observer buffer. Before each
stimulus, allocate one fixed-size `AttemptLedger` record from a preallocated array sized for
`20 warm-up + 200 measured + the approved maximum excluded attempts`. Callback and selected-end
hooks update only primitive fields and an atomic terminal state in that record; they do not do
file I/O, wait, sleep, enqueue work, acquire a measurement lock, call the evaluator, or change
the decision request.

The complete row ledger retains, for every warm-up, measured, and excluded attempt: `runId`,
attempt ordinal, sample ordinal when applicable, source-order identity when available,
`observationKind`, lifecycle generation, accepted runtime revision, package decision count,
allow/deny result, denying-rule count, commit status, publication status, every boundary
timestamp (`callbackStartNs`, `callbackReturnNs`, `selectedEndNs`, `quiescenceStartNs`, and
`quiescenceEndNs`) as raw integer nanoseconds, the signed durations, boundary-presence bits,
terminal state, exclusion reason, and synthetic fixture label. The host serializes the
already-closed ledger records to JSONL only after the run. If a ledger slot cannot be allocated or updated, record
`ABORT_LEDGER_CAPACITY` or `ABORT_LEDGER_WRITE_FAILURE` in the run manifest and stop before
dispatching another stimulus. Thus every dispatched attempt has one disposition or the run is
explicitly incomplete; there is no observer-drop count that can contradict the one-row rule.

### Aggregation

For a completed run, sort the valid selected durations, retained as raw integer nanoseconds,
in ascending order as `x[1] <= ... <= x[n]`. Use the nearest-rank method:
`rank = ceil(0.95 * n)` with one-based indexing, and `p95 = x[rank]`. With the proposed 200
valid measured rows, this is the 190th sorted row (zero-based index 189). Do not interpolate,
trim, pool runs, or infer any threshold, target timing, completion guarantee, or implementation
response. An aborted or incomplete run has no p95.

### Evidence, privacy, integrity, and deletion

- Keep one immutable `samples.jsonl` row for every warm-up and measured attempt, including
  excluded and timeout rows. Use only `T27_ALLOW`/`T27_DENY` and other synthetic identifiers;
  do not retain real package names, real rule names, or user data.
- Retain `metadata.json` with run id, exact start/end wall time, captured serial, commit/app
  version/build/fingerprint, device/API, flavor, harness revision, chosen A/B boundary,
  chosen D/R/E/M options, clock source, stimulus order, lifecycle generation, root/window
  conditions, terminal/exclusion totals, and the raw-file SHA-256 values.
- Keep device staging in the app-private measurement directory only until the host copy in
  `C:\Users\DELL\StudioProjects\curbox-android\.scratch\app-rule-enforcement\evidence\ticket27\<run-id>`
  has been verified byte-for-byte by hash. Delete the device staging copy at that point.
- Keep the host raw files in the restricted workspace through review and ticket closure.
  Delete them only after explicit user instruction or explicit ticket-archive approval, and
  record the deletion in the ticket. No encryption is required while the files remain in the
  restricted workspace; moving them outside it requires a separate approval and retention
  decision.
- Anchor the evidence integrity by committing a small `evidence-manifest.json` or ticket
  evidence update containing the exact SHA-256 values and run metadata. The manifest commit is
  the trusted reference; raw files need not be committed when their restricted workspace copy
  is retained.

### Explicit approval points

Before any measurement work, the user must explicitly choose:

1. Boundary **A** (`callback-to-evaluation-ready`) or boundary **B**
   (`callback-to-published-decision`). The outer full-service boundary is not selected.
2. Terminal/recovery deadlines: T1, T2, or user-supplied `D` and `R`.
3. Exclusion/abort cap: E0, E5, or E20.
4. Population mix: M1, M2, or M3.
5. The pinned serial/device/build identity, and the policy that the exact app version, source
   commit, and harness commit captured before samples are the final measurement
   implementation HEAD; any later code change invalidates the run.
6. The synthetic single-window fixture scope and its explicit limitation that it does not
   establish production Android-window or OEM representativeness.
7. Lossless preallocated ledger retention, restricted-workspace retention/deletion, and
   committed hash manifest.

No measurement, instrumentation verification, sample collection, p95 calculation, or
threshold decision may begin before those choices are approved.

- [x] Before collecting any measurement samples, write the revised protocol proposal above. No instrumentation was run and no samples were collected while writing it.
- [x] **MUST STOP** immediately after recording the protocol proposal and request explicit user approval. This revision did not verify the measurement path, run tests/Gradle, collect samples, or report a p95.
- [ ] After approval, verify that the approved measurement path can observe the selected boundaries without changing decision behavior solely to obtain a number, then run only the approved protocol and record the observed p95, exact conditions, population, retained evidence, and known limitations in the canonical documentation.
- [ ] Do not invent a pass or fail threshold, target timing, completion guarantee, or implementation response; if a threshold decision is needed, stop and ask the user for it.

**Scope:** Keep this as one vertical measurement slice within 150k context. Do not implement latency behavior or select a threshold.
