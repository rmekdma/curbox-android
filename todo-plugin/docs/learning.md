# Todo plugin guide

Read the [Todo plugin spec](../../docs/spec/todo-plugin.md) when changing account, dashboard, monitoring, notification, connection, grant, or settlement behavior.

## Host integration

`:plugin-contract` exposes the in-process host contract to bundled plugins. The
learning monitor consumes current host observations and shares the service
lifecycle. The contract passes observations without accessibility events or
nodes. Its StateFlow stays in the service process. Use the [AIDL guide](../../docs/CURBOX_API.md)
for external clients.

The UI runs in the main process. `AppBlockerService` starts and cancels the
monitor in `:app_blocker_service`. `PluginServiceHost` refreshes foreground
observations on a worker and publishes the current state. Callback silence alone
does not establish disconnection.

`:todo-plugin` owns the learning UI, API, monitor, notifications, permissions,
composition and storage formats. `:app` supplies scoped observations, stores and
atomic time transactions. Follow the [service safety rules](../../docs/architecture/features.md)
when changing host wiring. Contain feature errors and propagate coroutine
cancellation.

## State ownership

`LearningStorage` gets state and session stores from the plugin host. Use the
host's multi process DataStores for reads and writes from either process.
Session stores use Android Keystore encryption. Fake and real compositions
use separate stores and key aliases.

Manual screen work claims a revision before performing asynchronous work.
Monitor writes transform the latest document atomically. Keep their ownership
checks when changing lifecycle or retry handling. Todo award state is stored
through the host's plugin time API and accompanies the corresponding grant or
rollover transaction.

## Where to change behavior

Paths in the first four items are under `todo-plugin/src/main/java/`.

- Screen and manual requests (`neth/iecal/todo/LearningFragment.kt`, `LearningScreen.kt`).
- API, report parsing and subject apps (`neth/iecal/todo/HttpTodoApi.kt`, `TodoReportParser.kt`, `SubjectPackages.kt`).
- Monitoring and publication (`neth/iecal/todo/InitialMonitor.kt`, `LearningStore.kt`, `OrderedLearningPublisher.kt`) and device adapters (`neth/iecal/curbox/learning/`).
- Connections and awards (`neth/iecal/todo/TodoRuleSettings.kt`, `TodoAwardRepository.kt`, `TodoAwardState.kt`).
- Host runtime and stores (`app/src/main/java/neth/iecal/curbox/plugins/`) and service lifecycle (`services/AppBlockerService.kt` in the app package).

## Build and verification

The default composition is fake, including both app processes. Use it for all
tests and device verification; do not call the real Todo API or use real
credentials in tests. Fake debug has a shell guarded synthetic fixture provider
in `todo-plugin/src/learningFakeDebug/`. A fixture demonstrates Android integration with
synthetic responses, not real server compatibility.

On Windows with the repository's configured JDK, the usual checks are:

```powershell
.\gradlew.bat testFullDebugUnitTest
.\gradlew.bat :todo-plugin:testDebugUnitTest
.\gradlew.bat assembleFullDebug
```

An explicit `-PlearningReal=true` selects the real HTTP composition; Gradle
rejects test task requests in that mode. If an explicitly requested real build
is needed, use `-PlearningReal=true assembleFullDebug` for a debug build. The
default build and tests remain fake. Do not run a real API check as verification.

Device checks can observe service connection, foreground detection, permissions,
notifications and recovery using fake data. Record the Android version and
what was actually observed: a fixture response alone does not establish sound,
heads up, reboot behavior or behavior on another Android version. Existing
device test settings backup does not restore learning accounts or registrations;
do not treat it as a learning data backup. Run the learning device test only on
a dedicated test device: it changes fake learning data and does not restore prior
learning accounts or registrations. One useful flow is to register a synthetic
child with Math at 2 of 5 and observe a silent baseline, change the fixture to
5 of 5, put the harmless API tester in the foreground as the configured subject
app, wait for the configured regular interval, and verify completion in the
same child's notification. Existing unit tests cover other policy boundaries.

Run `todo-plugin/device-tests/test-device-learning-monitor.ps1` on a dedicated device with
`-LearningApkPath`, `-ExpectedApkSha256`, and `-BuildToolsDirectory` pointing to
the verified fake debug APK, its SHA256, and Android build tools. Install that
exact APK and the harmless API tester first, enable Curbox accessibility, and
remove existing learning registrations, and set the Math goal to 5 and the check
interval to 1 minute. The test checks the installed APK and
fake markers before changing test state. `device-tests/run-all-device-tests.ps1` skips this
test unless those artifact arguments are supplied using `-TargetApkPath` for the APK. The test checks Android
notification fields, not audible sound or heads up display.
