# 29: Xiaomi Pad Pro 2025 12.7 Android 15/16 and OEM deep-sleep final validation

**What to build:** Perform the final physical validation of the completed app rule enforcement behavior on the exact target model `Xiaomi Pad Pro 2025 12.7`, with Android major version 15 or 16 allowed, including the device's actual OEM deep-sleep and wake-recovery behavior. This is explicitly the last ticket in the sequence.

**Blocked by:** 21 — Long-boundary foreground decision closure; 22 — Recheck policy closure; 23 — Visibility-window closure; 24 — Callback flush closure; 25 — Guardian lifecycle closure; 26 — Debug fixture reliability closure; 27 — Callback and decision p95 measurement; 28 — Drain budget evidence and decision

**Status:** open (stopped on target hardware mismatch; protocol proposal recorded)

- [x] At execution start, capture the exact device identity, model, Android major and version, build identifier, and the build channel as actually installed. The channel is observed and recorded, not preselected; do not infer or require a stable channel. If the channel is not exposed, record that it is unavailable rather than assuming one.
- [x] If the model is not exactly `Xiaomi Pad Pro 2025 12.7` or the Android major is not 15 or 16, stop immediately without substitution and report the mismatch. Do not use another device as evidence.
- [x] After tickets 21–28 are complete and before any physical sleep run, write an objective deep-sleep protocol proposal covering entry-state proof, elapsed duration, wake stimulus, expected recovery outcome, and retained logs and metadata. Virtual doze is not physical deep-sleep evidence.
- [x] **MUST STOP** after recording the deep-sleep protocol proposal and request explicit user approval. Do not run the final validation or physical deep-sleep protocol until that approval is received.
- [ ] After approval, run only the approved protocol and verify the accepted long-boundary, recheck, visibility, callback, Guardian, and stop or drain outcomes using the actual device lifecycle. Record pass or fail evidence for each applicable outcome. (Pending target hardware availability and explicit user approval)
- [ ] Exercise the approved actual OEM deep-sleep and wake-recovery procedure and record the observed behavior, exact conditions, retained logs, and any limitation that remains. (Pending target hardware availability and explicit user approval)
- [ ] Update the canonical evidence and limitation records with the exact device identity, Android version, build identifier, observed channel, test conditions, results, and unresolved findings; do not close the ticket if required validation fails. (Device identity, Android version, build ID, observed channel, immediate halt, and unresolved findings recorded; test conditions and validation results remain pending physical execution on target hardware)
- [x] Make no new product threshold, device substitution, architecture change, or acceptance decision without asking the user first.

**Scope:** Keep this as one final vertical validation slice within 150k context. Do not start it before tickets 21–28 and the explicit protocol approval are complete.

## Execution start device capture — 2026-09-13

At execution start, the connected device state was queried via ADB:

| Property | Observed value | Source / Command |
| --- | --- | --- |
| Serial | `T811MA256GB23418064398` | `adb devices -l` |
| Transport ID | `4` | `adb devices -l` |
| Manufacturer | `Alldocube` | `getprop ro.product.manufacturer` |
| Brand | `Alldocube` | `getprop ro.product.brand` |
| Model | `iPlay50_mini_Pro` | `getprop ro.product.model` |
| Product | `iPlay50_mini_Pro` / `mssi_t_64_cn_armv82` | `getprop ro.product.name` / `ro.build.product` |
| Android release version | `13` | `getprop ro.build.version.release` |
| Android SDK | `33` | `getprop ro.build.version.sdk` |
| Android major version | `13` | Derived from `ro.build.version.release` |
| Build ID | `TP1A.220624.014` | `getprop ro.build.id` |
| Display ID | `iPlay50_mini_Pro_V2.0_20260301` | `getprop ro.build.display.id` |
| Build fingerprint | `Alldocube/iPlay50_mini_Pro/iPlay50_mini_Pro:13/TP1A.220624.014/1699256002:user/release-keys` | `getprop ro.build.fingerprint` |
| Build channel | `unavailable` (not exposed in system properties) | Observed via `getprop | grep channel` |

## Mismatch report and immediate execution stop

- **Target requirement:**
  - Model: exactly `Xiaomi Pad Pro 2025 12.7`
  - Android major: `15` or `16`
- **Observed connected device:**
  - Model: `iPlay50_mini_Pro` (Mismatch: `iPlay50_mini_Pro` != `Xiaomi Pad Pro 2025 12.7`)
  - Android major: `13` (Mismatch: `13` != `15` or `16`)
- **Action taken:**
  - As mandated by the ticket rule:
    > *"If the model is not exactly `Xiaomi Pad Pro 2025 12.7` or the Android major is not 15 or 16, stop immediately without substitution and report the mismatch. Do not use another device as evidence."*
  - Execution of device tests stopped immediately.
  - No alternative device was substituted as evidence.
  - No production source or architectural change was made.
  - Ticket 29 remains **OPEN** (not done) because target hardware validation has not been performed.

## Objective OEM deep-sleep and wake-recovery protocol proposal

In accordance with the pre-physical sleep run requirement, the following objective protocol is proposed for execution once the exact target model `Xiaomi Pad Pro 2025 12.7` (Android 15 or 16) is available:

### 1. Preflight and Environment Pinned Identity
- Verify target device identity:
  - Model must be exactly `Xiaomi Pad Pro 2025 12.7`.
  - Android major version must be `15` or `16`.
  - Record full build identifier, fingerprint, display ID, security patch, and observed install channel (or mark unavailable).
- Target package: `neth.iecal.curbox.debug` (FullDebug flavor) + test instrumentation.
- Preflight clean state: `pm clear` target packages, verify clean empty data directory under `files/`.
- Accessibility service enabled: `neth.iecal.curbox/.services.AppBlockerService`.

### 2. Entry-State Proof (Physical Sleep vs. Virtual Doze)
- True SoC suspend-to-RAM proof (virtual doze or `dumpsys deviceidle force-idle` is rejected as evidence):
  - Physical USB cable must be disconnected during the sleep interval (or ADB-over-Wi-Fi disconnected via `adb disconnect`) so that USB controller / gadget driver wakelocks do not inhibit hardware sleep.
  - Screen turned off via physical power button press.
  - Device left stationary on a flat surface to prevent accelerometer / motion sensor display wakes.
  - Post-wake physical sleep verification via hardware/kernel suspend metrics:
    - Kernel dmesg / logcat inspection for platform suspend entries (`PM: suspend entry (deep)`, `PM: suspend exit`).
    - `/sys/kernel/debug/suspend_stats` confirming `success` count increment and `failed` == 0.
    - Wakeup sources check confirming no blocking userspace wakelocks kept the SoC awake.

### 3. Elapsed Duration
- Minimum uninterrupted physical sleep duration: **15 minutes**.
- Zero ADB, network, or external electrical stimuli during this interval.

### 4. Wake Stimulus
- Physical power button press or hardware display wake.
- Device unlock (`input keyevent KEYCODE_WAKEUP`, dismiss keyguard).

### 5. Expected Recovery Outcomes (AR004 Scenarios)
The wake-recovery verification explicitly covers the three canonical AR004 scenarios:
1. **Scenario 1 — All-apps blocking boundary transition**:
   - Device enters sleep with app visible before all-apps block window begins.
   - All-apps block boundary passes while in sleep.
   - Upon wake, service reconciles past wall-clock boundary within the approved policy window and presents denial/lock screen without leaking unmonitored screen time.
2. **Scenario 2 — 30s+ guardian extension expiration**:
   - Directly usable quota exhausted, guardian extension granted (>30 seconds).
   - Sleep occurs and duration exceeds the guardian extension window.
   - Upon wake, evaluator immediately executes fail-closed denial, with no grace period re-leakage.
3. **Scenario 3 — Split-screen independent boundaries**:
   - Two apps visible simultaneously in split screen prior to sleep, each subject to independent rule boundaries.
   - Boundaries elapse during sleep.
   - Upon wake, both rules evaluate independently according to accepted visibility window ownership, without cross-app interference.
4. **Guardian Single-Instance Flow**:
   - In Guardian approval flows, wake recovery reuses any existing visible Guardian approval activity via `SINGLE_TOP` and updates denial rows without creating duplicate activities or stale choice rows.
5. **Lifecycle and Containment Safety**:
   - Zero crashes in `AppBlockerService` (`isDelayOver()` heartbeat and watchdog intact).
   - Clean recovery of persisted usage sessions (`recoverOpenSessions()`).
   - Zero post-destroy side effects or orphaned callbacks.

### 6. Retained Logs and Metadata
- Complete logcat captured immediately post-wake (`adb logcat -d -v threadtime`).
- `dumpsys power`, `dumpsys battery`, `dumpsys deviceidle` snapshots before and after the sleep interval.
- `dumpsys accessibility` verification that `AppBlockerService` remained bound and active.
- Device digests and output metadata recorded to `.scratch/app-rule-enforcement/evidence/ticket29/`.

> [!IMPORTANT]
> **MUST STOP REQUIREMENT**:
> Execution has stopped immediately due to device/OS mismatch and per the protocol approval requirement. The physical deep-sleep validation run will NOT proceed until:
> 1. The exact target model `Xiaomi Pad Pro 2025 12.7` (Android 15 or 16) is attached.
> 2. The user explicitly reviews and approves the deep-sleep protocol proposal.

## Unresolved findings and canonical status

- **AR004 (`Xiaomi Pad Pro 2025 12.7 실제 창 동작 테스트 부재`)**: Remains **테스트 공백** / **열림**.
- **Reported incident closed**: Remains **열림**.
- **Ticket 29 status**: Remains **open** (cannot be closed without physical target device validation).
