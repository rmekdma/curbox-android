# Ticket19 Phase 4 service lifecycle evidence

Status: **NO-GO for lifecycle-host materiality; ticket19 remains open.** The GO recorded by
`0fd5d473` was withdrawn after dual review. A qualifying framework tracer now reproduces no
canonical trigger, so the architecture approval gate remains closed.

Baseline: `db1066a0`, with ticket18 green and final.

## Rejected probe

The `0fd5d473` probe is not qualifying full-service lifecycle evidence and has been removed.
It instantiated an `AppBlockerService` subclass directly, attached a `Context` through
reflection, and invoked `onServiceConnected()` twice on the same object. Android did not bind,
disconnect, or rebind the accessibility service, and the sequence did not run as a framework
managed `:app_blocker_service` process lifecycle. It therefore cannot be described as an actual
service reconnect and cannot establish old and new service identities.

The probe also had these evidence defects:

- Its receiver list was an append-only registration-attempt log. It did not retain filter and
  active ownership only after successful registration, remove ownership only after successful
  unregister, or deliver a matching broadcast to prove a duplicate callback and external effect.
- It observed repeated non-AppRule registration attempts but did not compare the narrower
  per-feature idempotent setup remedy. Registration attempts alone establish neither trigger 4
  nor lifecycle-host materiality.
- Worker and barrier failures were not transported to the test thread independently of service
  error containment. A timeout or worker exception could be swallowed, so a green assertion did
  not prove that the intended interleaving completed.
- The cleanup fault occurred after AppRuleBlocker cleanup. It only showed that one later receiver
  cleanup was attempted after a `FocusModeBlocker` unregister failure; it did not prove that
  AppRuleBlocker scheduler cancellation survives an earlier cleanup failure.
- Private reflection and direct settings/runtime publication manipulation dominated the fixture.
  That bypassed the real settings and refresh boundary needed for service-level evidence.
- Making `AppBlockerService` open and foreground startup protected/open widened production APIs
  solely for this rejected harness. Those changes have been removed.

No result or log count from the rejected probe is retained as trigger evidence. No qualifying
full-service lifecycle test remains after the probe's deletion. The retained trigger 1 and 3
observations below are limited to the in-process component/manual harness scope and are not
framework service evidence.

## Corrected 2026-09-07 feasibility record

The earlier force-stop branch is not platform-blocker evidence. The command order was disable,
`adb shell am force-stop neth.iecal.curbox.debug`, then write the Curbox component. The package
state was `stopped=true` when ActivityManager emitted `Unable to launch app ... for service ...:
process is bad`. An explicitly stopped package is not eligible for that rebind attempt. The run
did not clear the stopped state with an app launch before enabling, so the procedure was invalid
for a distinct-process lifecycle conclusion. The old shell capture did not retain per-command
timestamps; none are reconstructed here.

The supported Gradle task also has narrower behavior than its name suggests. Its implementation
writes only Curbox to `enabled_accessibility_services`, replacing the complete list, and launches
the app. It does **not** write `accessibility_enabled`. Therefore
`installAndGrantAccessibilityFullDebug` must not be used as a list-preserving enable/restore
primitive.

There was an external user action after that attempt. The user changed Accessibility settings and
removed Lock Me Out because it was malfunctioning. Every later observation in that interval was
confounded by that user change and cannot be attributed to the shell sequence. Curbox cleanup did
succeed. Lock Me Out was removed by the user, must not be restored, and creates no restoration
obligation. The preserved user baseline for the qualifying run below was exactly:

```text
enabled_accessibility_services=com.safeincloud/.autofill.chrome.ChromeAutofillService
accessibility_enabled=1
```

## Retained debug-only framework tracer

The replacement tracer is target `src/debug` and `src/androidTest` only. The debug manifest adds
a signature-permission-protected bound observer service and a runtime shell/signature-checked
provider in `:app_blocker_service`. A debug `CoreComponentFactory` observes the real framework
instantiation of `AppBlockerService`; it does not instantiate, attach, subclass, open, or directly
invoke the production service. Reflection remains inside the debug APK and reads the actual
service, AppRuleBlocker, successful receiver-lifecycle registrations, coroutine scopes, and
service/receiver identities. Shell control uses the provider; the signed test APK uses AIDL.

The controller stores the exact initial service list and global flag, launches the app and verifies
`stopped=false`, merges Curbox without removing SafeInCloud, and performs each framework change in
this order:

```text
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services <exact merged or restored list>
adb shell settings put secure accessibility_enabled <exact desired flag>
```

Its `finally` repeats that order with the exact starting list and flag. It never adds or restores
Lock Me Out.

## Qualifying device run (2026-09-08)

Device: `T811MA256GB23418064398`, `iPlay50_mini_Pro`, Android 13/API 33.

Build/install command and result:

```text
$env:JAVA_HOME='C:\Users\DELL\.jdks\jbr-21.0.11'
.\gradlew.bat :app:installFullDebug
Installed APK 'app-full-debug.apk' on 'iPlay50_mini_Pro - 13'
BUILD SUCCESSFUL in 1m 2s
```

The first controller attempt started at `2026-09-08T05:50:18.7133900+09:00` from the exact
SafeInCloud-only baseline. It wrote global `0`, the merged SafeInCloud+Curbox list, then global `1`
at `05:50:22.110` through `05:50:22.451`. It timed out because the script inspected only the first
line of the multiline `Bound services` block. Its `finally` restored SafeInCloud-only/global `1`
at `05:50:54.088` through `05:50:54.617`. A manual diagnostic using the same ordered settings
writes then proved the platform bind at `05:51:50.441`: `dumpsys accessibility` listed both
SafeInCloud and `Curbox App Blocker` under `Bound services`, the package was `stopped=false`, and
`pidof neth.iecal.curbox.debug:app_blocker_service` returned `18594`. This was a parser defect, not
a platform limitation.

After correcting the multiline parser, the exact qualifying command was:

```text
& C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe `
  -NoProfile -File app\src\androidTest\ticket19-framework-tracer.ps1 `
  -Serial T811MA256GB23418064398
```

The final run began at `2026-09-08T05:54:58.8193111+09:00` from SafeInCloud-only/global `1`.
The app launch completed and package state was `stopped=false` at `05:55:02.109`. Curbox was merged
at `05:55:02.223`; ordered writes completed by `05:55:02.554`; framework bind was observed at
`05:55:02.675`.

Observed results:

- Initial target-process PID `19694`, service generation `2`, service identity `27349121`, five
  successful active AppRule receiver/filter registrations, active service/protection scopes, and
  setup ready. The refresh receiver belonged to PID `19694` in ActivityManager's `ReceiverList`.
- Baseline refresh at `05:55:05.150`: `Broadcast completed: result=0`, exactly one framework
  `Deliver +4ms #0` to PID `19694`, and exactly one runtime publication external effect.
- The runtime barrier reported `ARMED` then `ENTERED` before lifecycle mutation. Framework disable
  completed at `05:55:13.918`; IPC then reported AppRule destroyed, lifecycle generation `2`, zero
  active receiver registrations, and both service/protection scopes inactive. Releasing the
  barrier produced no post-destroy second publication or transported failure.
- Same-PID framework enable created service generation `3`, identity `231067270`, and five new
  active receiver identities while retaining PID `19694`. A matching refresh had exactly one
  `Deliver +3ms #0` and one runtime publication.
- Reapplying only `AppRuleBlocker.setupReceivers()` retained five active registrations and the same
  receiver identities. The next matching refresh again had exactly one `Deliver +4ms #0` and one
  runtime publication. This is the required narrower per-feature idempotent comparison.
- The named injected runtime-publication failure crossed IPC deterministically as
  `runtime_publication_injected`, `IllegalStateException`, with one framework delivery and one
  runtime-publication attempt. It was reset before subsequent lifecycle work.
- Target-process termination changed PID `19694` to `20458` and process token
  `3feec10a-8cad-4a5b-a57d-848130ac4f05` to
  `ac1dd45f-65b0-421a-b6a7-32b154a7a786`. Package state remained `stopped=false`; Android
  automatically rebound the accessibility service. The new process had service generation `1`,
  identity `29036551`, five new active receiver identities, and exactly one `Deliver +5ms #0` plus
  one runtime publication for the matching refresh.
- Final framework disable completed at `05:55:49.861`. IPC reported destroyed, zero active
  receivers, inactive scopes, and no failures. ActivityManager had no active AppBlockerService
  `ServiceRecord`; its remaining connection-history rows were marked `DEAD`.
- The controller printed `PASS canonical lifecycle trace completed without duplicate
  callback/effect` at `05:55:51.1847097`. Its `finally` restored the exact initial state and at
  `05:55:52.9523292` printed SafeInCloud-only/global `1`.

After adding each successful lifecycle-registration object's identity to the IPC snapshot, the
final validation run (`06:25:44.423` through `06:26:40.503`) also passed. It directly reported five
nonzero registration identities while active and zero registration identities after teardown,
changed PID `22235` to `22720`, and again restored SafeInCloud-only/global `1`.

This run does not claim trigger 1. No fault was ordered before AppRule cleanup, so no AppRule
cleanup-survival conclusion follows from the injected runtime-publication failure.

## Independent canonical trigger status and decision

1. **Cleanup exception bypass:** still unverified at the required fault ordering. No claim.
2. **Second independent scheduler caller:** absent from the production tree; no duplicate owner
   was added.
3. **Post-guard destroy/setup side effect:** not reproduced. The entered barrier, actual framework
   disable, complete teardown, and release produced no second publication.
4. **Stale service/receiver ownership after reconnect:** not reproduced. Same-PID rebind replaced
   service and receiver identities cleanly; distinct-PID rebind replaced process, service, and
   receiver identities; each matching broadcast produced one delivery and one external effect.

Decision: **NO-GO for lifecycle-host materiality; ticket19 remains open and the architecture gate
remains closed.** The qualifying baseline reproduced no canonical trigger, and the narrower
per-feature idempotent setup also retained one delivery/effect. A lifecycle host therefore has no
demonstrated defect to remedy. No lifecycle host, coordinator, integration, OEM work, or
implementation ticket is authorized by this result.

## Verification

- `:app:connectedFullDebugAndroidTest` filtered to `Ticket19ObserverBinderTest`: 1/1 passed on the
  physical device.
- `testFullDebugUnitTest assembleFullDebug assemblePlaystoreDebug assembleFdroidDebug`: passed in
  3m 44s.
- `assembleFullRelease assemblePlaystoreRelease assembleFdroidRelease`: passed in 14m 22s.
- `rg` found no Ticket19 observer class, authority, permission, or debug component factory in any
  Full, Playstore, or F-Droid release merged manifest.
- `aapt2 dump xmltree` found none of those names in each release APK manifest. `dexdump` found no
  Ticket19 observer/AIDL class in two Full, three Playstore, or two F-Droid release dex files.
- The builds emitted the repository's existing KSP/Kotlin compatibility and deprecation warnings;
  no new build failure was reported.
