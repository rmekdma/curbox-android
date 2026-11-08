# Learning feature

Curbox hosts the Todo learning screen and monitor in the same APK. The screen
supports sign in, goals, manual reports and date selection. Watching is enabled
per child; the automatic monitor uses today in `Asia/Seoul`, regardless of the
date selected on the screen. Learning goals do not change Curbox blocking rules.

## Monitoring behavior

- Registration starts an initial check of subscribed subjects with positive
  goals. It does not require the subject app to be in front or the screen to be
  on, but authentication, service availability and applicable permissions are
  required. The first valid report is a silent baseline.
- Regular checks require an unlocked screen and a confirmed foreground subject
  app. They exclude unregistered children, unsubscribed subjects, completed
  goals and zero goals. Initial and regular report work is serialized and shares
  the configured interval; an initial report consumes that interval.
- A zero goal is locally complete without a report. An unknown report is not a
  zero count. A later incomplete to complete transition requests an achievement
  notification; a baseline or completion caused by lowering a goal is silent.
- An initial catalog or report failure waits for an explicit retry. Its failure
  state survives restart, date or permission changes, and unregistering and
  registering the same child. A later temporary report failure after a valid
  baseline may retry on a subsequent eligible cycle. Authentication failure
  requires sign in again. Missing accessibility or notification permission
  pauses monitoring while retaining registrations.
- Each registered child has a status notification. A new achievement updates
  that child's notification; actual sound and heads up depend on Android and
  the user's notification channel settings.

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
The observation is current state, not an event history. No callback by itself
does not prove the service is broken. The monitor assumes a single full screen
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
do not treat it as a learning data backup. For an end to end check, use an
isolated fake environment, such as a dedicated emulator, or a recovery method
that understands learning state. One useful flow is to register a synthetic
child, observe an initial failure, retry it, and verify the displayed status
and child notification. Existing unit tests cover the other policy boundaries.
