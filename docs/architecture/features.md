# Feature subsystem map and service architecture

Architecture details for Curbox accessibility features, tracking, and service protection.

## Accessibility host (`AppBlockerService`)

`services/AppBlockerService.kt` hosts all blockers, trackers, and anti-stimulants. It extends `services/BaseBlockingService.kt`, which manages the foreground notification, heartbeat, global actions, and delay evaluation.

### Feature lifecycle pattern

1. Instantiate the feature as a field in `AppBlockerService`.
2. Initialize it in `onServiceConnected()`.
3. Register broadcast receivers after setup.
4. Execute lightweight checks directly in `onAccessibilityEvent`; send node-walking work to the background worker (`Channel.CONFLATED`).
5. Unregister receivers and release resources in `onDestroy()`.

Broadcast actions use companion constants (e.g. `AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER`). Settings screens write to `DataStoreManager` before dispatching the refresh broadcast.

## Feature locations

- `blockers/AppBlocker.kt`: Schedules and usage limits. Packages can belong to multiple groups and block when any group condition matches.
- `blockers/ReelBlocker.kt`: Instagram reels, YouTube shorts, and Facebook reels. Runs in the background worker.
- `blockers/KeywordBlocker.kt`: Blocks keywords based on website observations from `WebsiteUsageTracker`; does not scan screen text. Unsupported browser checks run in the background worker.
- `blockers/FocusModeBlocker.kt`: Temporary app and keyword blocking during focus sessions.
- `blockers/BrowserBlocker.kt` & `blockers/AntiUninstallBlocker.kt`: Browser navigation restrictions and device admin protection.
- `blockers/uihider/`: Overlay-based UI hiding and script language parser. Node picking uses `services/NodePickerService`.
- `trackers/`: App usage, website usage, and short video counts. `WebsiteUsageTracker` includes a 15-second heartbeat for browsers lacking URL change events.
- `anti_stimulants/`: `AutoDnd`, `GrayScaleFilter`, and `MindfulMessage`. DND state requests are aggregated via `AppBlockerService.syncDndState()`.
- `hardcoded/`: View IDs and per-app configuration for reel apps and browsers. Never inline view IDs in feature code.
- `api/`: Exported AIDL service, user approval flows, and auth storage. See `docs/CURBOX_API.md` and `:apitester`.

## User-facing blocking screens

User-facing lock screens route through `ui/activity/WarningActivity` with the corresponding `Constants.WARNING_SCREEN_MODE_*` value. App rule guardian approvals are the exception: `AppRuleBlocker` opens `GuardianApprovalActivity` directly so a single rejection produces only one lock screen.

## Service protection

`ServiceWatchdogJob`, `utils/ServiceProtectionManager`, `receivers/BootReceiver`, and the `BaseBlockingService` heartbeat keep the accessibility service active and detect unexpected stops. Anti-uninstall UI lives in `ui/fragments/main/reducers/advanced/`.
