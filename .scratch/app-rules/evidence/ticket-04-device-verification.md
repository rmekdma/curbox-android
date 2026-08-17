# Ticket 04 device verification procedure

This procedure is required for the representative contributor, service and warning-screen
checks. The SDK command
`C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe devices` reported no
attached device or emulator on 2026-08-18, so the run remains pending and these steps record the
reproducible procedure.

## Setup

1. Start an Android 12 or newer emulator, or attach one test device with USB debugging enabled.
2. Continue only when the exact `adb devices` command above shows one device in state `device`.
3. Run `./gradlew installAndGrantAccessibilityFullDebug` with
   `C:\Users\DELL\.jdks\jbr-21.0.11`, enable app usage tracking, and verify that the Curbox
   accessibility service is enabled.
4. Record the device model, Android version, APK commit SHA, and three ordinary launchable test
   apps. Use one app as the target and the other two as contributors.

## Contributor union and option combinations

1. Create contributor group A containing contributor app 1 and contributor group B containing
   contributor app 2. Create a target group containing the target app.
2. Create four active rules for the target, using the same direct allowance and appropriate
   contributor groups: both options off, condition only, earning only, and both options on.
3. Put the same contributor app in both contributor groups and repeat the earning check. Confirm
   its raw foreground time is counted once within each rule, while the two rules sharing a group
   each receive the same independent benefit.
4. Use a condition window that starts after the contributor session. Confirm contributor time
   from the whole current use day counts even when it is outside the target rule's active window;
   before the threshold the normal allowance is zero, and after it the direct allowance plus any
   enabled one-to-one earning is shown.
5. Put the target app in a contributor group and create a pair of rules that reference each
   other. Confirm the rules save and use only raw foreground sessions, without recursive
   allowance transfer or a crash.

## Commit-before-enforcement and failure isolation

1. With contributor app 1 visible, switch immediately to the target app. Confirm the contributor
   foreground session is ended and persisted before the target decision, and that the target
   warning reflects the newly earned minutes on that same accessibility event.
2. Delete a contributor group that is still referenced by a rule. Confirm group deletion is
   allowed, the missing ID remains in the rule, the groups screen shows a repair-needed state, and
   the affected target does not receive normal allowance. Verify an unrelated target rule remains
   unaffected.
3. Capture the warning screen. It must distinguish condition progress and threshold, earned
   allowance, direct allowance, and final remaining allowance.
4. In a debug fixture, make the session read or warning activity launch fail once. Confirm the
   service logs a nonfatal error, remains enabled, and processes the next accessibility event.

Record timestamps, package names, raw session rows, rule IDs, the warning screenshot, and the
service logcat excerpt. Attach the result to this file after a device run.
