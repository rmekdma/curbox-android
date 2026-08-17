# Ticket 03 device verification procedure

This procedure is required for the representative UI, accessibility-service and package-install
checks. The SDK `adb devices` command reported no attached device or emulator on 2026-08-17, so
these steps record the exact run still required.

## Setup

1. Start an Android 12 or newer emulator, or attach one test device with USB debugging enabled.
2. Continue only when `C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe devices`
   shows exactly one device in state `device`.
3. Run `./gradlew installAndGrantAccessibilityFullDebug` with JBR 21.0.11, enable app usage
   tracking, and verify that the Curbox accessibility service is enabled.
4. Record the device model, Android version, APK commit SHA and the package names of two ordinary
   launchable test apps plus Android Settings.

## Composite scope and refresh

1. Create group A containing test app 1 and group B containing test app 2. Create an active rule
   with all apps and group A included, group B excluded, and zero allowance. Confirm app 1 and
   Android Settings are blocked while app 2 is not; confirm Curbox, the launcher, System UI and
   the current IME are never blocked.
2. Install a new launchable test app while the service is running. Confirm the all-apps rule
   includes it after the package refresh without editing or recreating the rule.
3. Open the app picker and confirm the same essential packages are absent while Android Settings
   remains selectable. Reopen the editor and confirm included and excluded group selections persist.

## Weekdays, ranges and allowance

1. Create one rule for Monday 22:00 to 06:00 with zero allowance. Test Monday 21:59, Monday
   22:00, Tuesday 05:59 and Tuesday 06:00, recording block decisions and timestamps.
2. Add two touching ranges and one overlapping range on separate weekdays. Confirm the ranges
   consume one shared allowance without double counting; confirm sessions outside all ranges remain
   in global statistics but do not consume the rule allowance.
3. Create a rule with equal start and end times and verify it is active for the full day. Create
   two overlapping rules for one app and verify each allowance is consumed independently and one
   denial blocks the app.

## Integrity and deletion

1. Attempt to delete a group referenced by an include or exclude target. Confirm simple deletion
   is refused and the dialog requires either removing references or deleting dependent rules.
2. Write a malformed snapshot in a debug test fixture, restart the service and confirm the UI
   reports invalid configuration, the last valid runtime snapshot remains active, and unknown apps
   are not blocked from a cold start.
3. Capture screenshots of the picker/editor, block decisions, outside-range statistics and the
   invalid-state screen, plus logcat around package refresh and service restart.
