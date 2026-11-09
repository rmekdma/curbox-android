# Learning feature

Curbox hosts the TODO School screen and monitor in the same APK. The screen
supports sign in, goals, manual reports for today. Watching is enabled
per child; the automatic monitor uses today in `Asia/Seoul`, as does the dashboard. Learning goals do not change Curbox blocking rules.

The dashboard opens account changes, the check interval and each child’s subject goals in separate
settings dialogs. Goal changes are validated and saved together for the account,
session and subject identities captured when the dialog opened. All three subject
goals can be configured, including unsubscribed subjects; saving a goal does
not add a subscription or report request.

The dashboard uses today in Seoul and has no date selector. Each subject row
shows the newest available report for today from manual checks or the same
account's monitor, with the count and relative check time from that same report.
When today has no valid report, the row shows `? / goal`. Older report values
and check times are not displayed. A selected successful report
at its goal shows a green check; a zero goal shows a local check without a
fabricated count. Manual and automatic errors are labeled separately. The
relative time updates without rebuilding the settings dialog.

## Monitoring behavior

- Registration starts an initial check of subscribed subjects with positive
  goals. It does not require the subject app to be in front or the screen to be
  on, but authentication, service availability and applicable permissions are
  required. The first valid report is a silent baseline.
- Regular checks require an unlocked screen and a confirmed foreground subject
  app. They exclude unregistered children, unsubscribed subjects, completed
  goals and zero goals. Initial and regular report work is serialized and shares
  the configured interval; an initial check consumes the common interval.
- A zero goal is locally complete without a report. An unknown report is not a
  zero count. A later incomplete to complete transition requests an achievement
  notification; a baseline or completion caused by lowering a goal is silent.
- An initial catalog or report failure waits for an explicit retry; an explicit
  sign in can also start a new initial check. Pulling down on the signed in
  dashboard refreshes today and explicitly retries eligible failed
  watching checks for today. Failure state survives restart,
  date or permission changes, and unregistering and registering the same child.
  A later temporary report failure after a valid baseline may retry on a
  subsequent eligible cycle. Authentication failure requires sign in again.
  Missing accessibility or notification permission pauses monitoring while
  retaining registrations.
- Each registered child has a status notification. A new achievement updates
  that child's notification; actual sound and heads up depend on Android and
  the user's notification channel settings. The title is the child name. Each
  subject shows today's count and goal, with a check mark when complete. Zero
  goals show a check mark; missing or older reports show as not checked. A
  failed report does not show a completion check for a positive goal.

## Boundaries and lifetime

`:plugin-contract` defines `CurboxHost`, `HostObservation` and `CurboxPlugin`.
This is an internal Kotlin contract in the accessibility service process, not
the exported AIDL API described in [CURBOX_API.md](../CURBOX_API.md). It passes
current observations, not accessibility events or nodes. Its StateFlow is not
a cross process transport.

The UI runs in the main process. `AppBlockerService` starts and cancels the
monitor in `:app_blocker_service`; `LearningServiceHost` supplies observations.
The host refreshes observations on a worker. Unknown foreground, screen off,
lock or failed observation cannot establish a subject app for regular checks.
The observation is current state, not an event history. Callback silence alone
does not establish disconnection. The monitor assumes a single full screen
app; split screen and multiple window focus selection are not supported.

`:todo-plugin` owns learning UI, API and report handling, monitor policy and
the `LearningStore` contract. `:app` owns composition, Android host, storage,
permission and notification adapters. Service errors are contained and
coroutine cancellation propagates; follow the service lifecycle and safety
rules in [features.md](features.md) and `AGENTS.md` when changing the wiring.

## State and ownership

`LearningStorage` gets the learning state and session stores from
`DataStoreManager`. They are multi process DataStores, so both processes observe
the same persisted document rather than sharing an in memory object. Session
data is encrypted with an Android Keystore key. Fake and real compositions use
separate state and session files and key aliases.

Manual screen changes and monitor changes have different ownership. The store
claims a revision for manual work and commits only while that revision and
expected session remain valid. Monitor updates run atomically against the
latest document. Monitor owner, registration, session, goal, date and permission
checks fence stale work before it changes current state or publishes effects.
Preserve these checks and the persisted initial failure latch when changing
registration, retries or lifecycle recovery. Resetting a baseline does not
itself clear historical reports or retry latches.

## Where to change behavior

- `todo-plugin/src/main/java/neth/iecal/todo/LearningScreen.kt` and
  `LearningFragment.kt`: screen actions, manual state and presentation.
- `HttpTodoApi.kt`, `TodoReportParser.kt` and `SubjectPackages.kt` in that
  package: API adapter, report interpretation and subject app mapping.
- `InitialMonitor.kt`, `LearningStore.kt` and `OrderedLearningPublisher.kt` in
  that package: scheduling, persistent monitor state and ordered publication.
- `app/src/main/java/neth/iecal/curbox/learning/`: host observation, storage,
  permissions, notifications and composition adapters. The service lifecycle
  is wired in `services/AppBlockerService.kt`.

Reuse the learning API and store contracts for screen and monitor changes.
The host observation contract contains no Todo account or subject policy.

## Build and verification

The default composition is fake, including both app processes. Use it for all
tests and device verification; do not call the real Todo API or use real
credentials in tests. Fake debug has a shell guarded synthetic fixture provider
in `app/src/learningFakeDebug/`. A fixture demonstrates Android integration with
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

Run `device-tests/test-device-learning-monitor.ps1` on a dedicated device with
`-LearningApkPath`, `-ExpectedApkSha256`, and `-BuildToolsDirectory` pointing to
the verified fake debug APK, its SHA256, and Android build tools. Install that
exact APK and the harmless API tester first, enable Curbox accessibility, and
remove existing learning registrations, and set the Math goal to 5 and the check
interval to 1 minute. The test checks the installed APK and
fake markers before changing test state. `run-all-device-tests.ps1` skips this
test unless those artifact arguments are supplied. The test checks Android
notification fields, not audible sound or heads up display.
