# 27: Callback and decision p95 measurement

**What to build:** First propose a reproducible protocol for measuring the representative callback-to-decision p95 for the completed app rule path. Only after explicit user approval may the approved protocol be run and its p95 recorded. The measurement is evidence only and must not become a product requirement without a separate user decision.

**Blocked by:** 21 — Long-boundary foreground decision closure; 22 — Recheck policy closure; 23 — Visibility-window closure; 24 — Callback flush closure; 25 — Guardian lifecycle closure; 26 — Debug fixture reliability closure

**Status:** awaiting-user-approval

## Pre-approval protocol proposal — 2026-09-10

**Proposal status:** awaiting explicit user approval.

This section is a protocol proposal only. No measurement path was run, no samples were
generated or collected, and no p95 was calculated. The remaining
`WarningActivityLifecycleTest::warningIsFinishedAfterItLeavesTheForeground` failure is
outside Ticket 20's frozen inventory and remains unassigned pending a separate parent or
user ownership decision.

### Metric and sample identity

- The primary metric is **callback-to-decision latency**.
- `callbackStart` is the monotonic timestamp taken immediately before the existing
  `AppRuleBlocker.doAppRuleCheck(event)` action is invoked at the synchronous app-rule
  service boundary. This excludes the preceding `AppUsageTracker` work and starts at the
  app-rule callback itself.
- `callbackReturn` is the timestamp taken immediately after `doAppRuleCheck(event)` returns.
  `callbackReturn - callbackStart` is retained as the callback handoff diagnostic.
- `decisionReady` is the timestamp taken when the matching
  `SerializedDecisionWorker` `onEvaluation` seam receives the `AppRulesEvaluation` produced
  by `AppRuleEnforcement.check()`. At this point the worker has completed the required
  visible-session reconciliation and persistence read/commit ordering, and the evaluation is
  available, but recheck-plan publication and the later Handler/WarningActivity/Guardian
  side effects have not started.
- The primary sample duration is `decisionReady - callbackStart`. The worker segment
  `decisionReady - callbackReturn` is retained as a diagnostic only; neither metric is a
  product requirement.
- One sample is one accepted `DecisionRequest` with `reason == REAL_EVENT`, correlated by
  its existing `SourceOrderIdentity`, that produces exactly one evaluable package decision
  and reaches `decisionReady`. The sample is not one row per log line, retry, recheck, or UI
  effect.

### Monotonic clock and passive correlation

- On the Android device, all three timestamps use `SystemClock.elapsedRealtimeNanos()`.
  Durations remain integer nanoseconds in raw evidence. This clock is monotonic and includes
  device sleep; wall-clock timestamps are metadata only and are never used for subtraction.
- The diagnostic adapter correlates the callback-local start/return timestamps with the
  request's existing `SourceOrderIdentity` when the request is formed, then correlates that
  identity with the existing evaluation seam. It does not add timing fields to the decision
  contract and does not make a second evaluator or persistence call.
- The instrumentation is passive: no blocking I/O, sleeps, latches, extra locks, scheduler
  posts, queue changes, policy branches, or UI interception are allowed on the callback or
  worker path. A bounded nonblocking observer buffer may receive primitive records; if it is
  full, the record is counted as an observer drop and is not silently treated as a sample.

### Warm-up, iterations, and stimulus conditions

- Use one declared device, one app build/flavor, one commit, and one service connection per
  run. Do not pool devices, OS versions, builds, or service lifecycles into one p95.
- Before warm-up, record the device/build metadata and the fixed app-rule snapshot. The
  service must be connected and ready, the screen must be interactive and unlocked, and no
  refresh, reconnect, screen-off/wake, user-present, synthetic recheck, pending lifecycle
  replacement, WarningActivity, or Guardian approval screen may be active at the start of a
  sample.
- Use only real `TYPE_WINDOW_STATE_CHANGED` events with a nonblank nonessential package.
  The fixture packages and their rule state must be declared in run metadata before the run;
  the proposal does not select package names or a device for the user.
- Run 20 completed eligible samples as warm-up and discard their sample durations. After the
  warm-up queue is quiescent, collect exactly 200 completed eligible samples. Dispatch the
  next stimulus only after the preceding sample reaches `decisionReady`; samples are not
  intentionally overlapped and no artificial sleep is inserted between them.
- The primary population is single-package, steady-state app-rule evaluation. Both allowed
  and denied outcomes are retained when they satisfy the same boundary; the outcome and
  denying-rule count are metadata, not filters. A multi-package outcome, unknown/essential
  root, or non-evaluable evidence is an excluded attempt and is counted by reason.
- The following are excluded from the p95 population and counted separately: invalid or
  ignored events, worker submissions rejected as not-ready/stale/overlapping, synthetic
  rechecks, refresh/reconnect/screen lifecycle observations, cancellation or generation
  invalidation, recoverable outcomes without an evaluation, outcomes with no package
  decision, multi-package outcomes, and observer-buffer drops. No duration is removed merely
  because it is large; there is no outlier cutoff.

### Aggregation

- For each completed run, sort the valid primary durations in ascending order as
  `x[1] <= ... <= x[n]`.
- Use the nearest-rank method: `rank = ceil(0.95 * n)` using one-based indexing, and report
  `p95 = x[rank]`. For the proposed 200-sample population, this is the 190th sorted sample
  (zero-based index 189). No interpolation, trimming, cross-run pooling, or threshold is
  applied.
- Report the primary callback-to-decision p95 only after the approved run completes. Report
  the callback-handoff and worker-segment distributions as diagnostic context, together with
  `n`, exclusion counts, and observer-drop count. This protocol proposes no pass/fail
  threshold, target timing, completion guarantee, or implementation response.

### Evidence retention

- Retain one immutable `samples.jsonl` row for every warm-up, valid, and excluded attempt;
  mark warm-up rows explicitly and do not delete them. Each row contains run id, attempt and
  sample ordinal, source-order identity, observation kind, package fixture label, lifecycle
  generation, accepted runtime revision, callback start/return, decision-ready timestamps,
  raw nanosecond durations, package-decision count, allow/deny result, commit/publication
  status, and exclusion reason when applicable.
- Retain a `metadata.json` beside the raw rows with run start/end wall time, device
  manufacturer/model, Android release/API/build fingerprint, app version and flavor, source
  commit, fixture/rule snapshot identifier, declared warm-up and iteration counts, clock
  source, stimulus order, lifecycle generation, screen/keyguard/doze state, exclusion totals,
  observer drops, and the SHA-256 of the raw evidence files.
- Store the files under an app-specific measurement directory for the run, pull the files
  unchanged to the host after the run, and preserve the host copy with the ticket evidence.
  Do not store measurement rows in Room, DataStore, or production analytics, and do not
  expose package names beyond the approved fixture metadata.

### Explicit approval points

Approval is required for the complete proposal, especially these choices: the callback and
`onEvaluation` boundaries; 20 warm-up plus 200 eligible samples; one-at-a-time real-event
stimulus; single-package population with multi-package and lifecycle work excluded; the
nearest-rank `ceil(0.95 * n)` aggregation; the device and fixture package/rule state to use;
and the passive bounded-observer retention scheme. No measurement, sample collection, or p95
reporting may begin until these choices are explicitly approved.

- [x] Before collecting any measurement samples, write the protocol proposal above covering the event boundaries, clock source, warm-up policy, iteration count, sample population and conditions, aggregation method for p95, and evidence retention including raw samples and run metadata. No samples were collected while writing it.
- [x] **MUST STOP** immediately after recording the protocol proposal and request explicit user approval. The measurement path was not verified, no samples were generated or collected, and no p95 was reported.
- [ ] After approval, verify that the proposed measurement path can observe the stated boundaries without changing decision behavior solely to obtain a number, then run only the approved protocol and record the observed p95, exact conditions, population, retained evidence, and known limitations in the canonical documentation.
- [ ] Do not invent a pass or fail threshold, target timing, completion guarantee, or implementation response; if a threshold decision is needed, stop and ask the user for it.

**Scope:** Keep this as one vertical measurement slice within 150k context. Do not implement latency behavior or select a threshold.
