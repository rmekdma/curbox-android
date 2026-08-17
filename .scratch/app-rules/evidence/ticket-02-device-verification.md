# Ticket 02 device verification procedure

This procedure is required for acceptance criterion 31. It was prepared because `adb devices`
reported no attached device or emulator on 2026-08-17; it is not a substitute for the run.

## Setup

1. Start an Android 12 or newer emulator, or attach a test device with USB debugging enabled.
2. Run `adb devices` and continue only when exactly one device is listed as `device`.
3. Install and grant the accessibility service with
   `./gradlew installAndGrantAccessibilityFullDebug`.
4. In Curbox, enable app usage tracking, set the global use-day reset to a test time a few
   minutes in the future, and enable an active time rule for two test applications.
5. Capture the package names of the two test applications and verify that the accessibility
   service is enabled before opening either application.

## Split-screen and boundary checks

1. Open test application A, then use the system split-screen action to place test application B
   beside it. Leave both visible for exactly 60 seconds, then capture the screen and the Curbox
   usage page.
2. Confirm that each application has one 60-second session, the group total is 120 seconds, and
   no Curbox or `com.android.systemui` row is present.
3. While both applications remain visible, switch focus between them several times. Confirm that
   neither application's session is closed merely because focus changed.
4. Open the keyboard in one pane and show a system overlay. Confirm that IME and overlay windows
   do not create rows and do not close the other application session.
5. Turn the screen off for 30 seconds and back on. Confirm that no session time accrues while the
   screen is off and that both visible applications resume independently afterward.
6. Set the reset time one minute ahead, keep both panes visible across the boundary, and confirm
   that the old rows end at the boundary and new rows begin in the new use day.
7. Change the reset time again while both panes are visible. Confirm that the old generation is
   not reinterpreted and that only newly observed time is written to the new generation.

## Restart, failure containment, and evidence

1. With a test application visible, stop and restart the accessibility service. Confirm that a
   saved session is restored before a new row is started and that no unsaved post-crash tail is
   invented.
2. Export or record the Curbox usage page, the rule remaining-time page, and the logcat excerpt
   around the reset and restart. The evidence must include timestamps, package names, and the
   database rows for both applications.
3. If the window query or storage operation is deliberately made to fail in a debug build,
   confirm that the service remains alive and processes the next accessibility event.

Record the device model, Android version, build variant, APK commit SHA, exact reset times, and
the observed per-package durations with the test result.
