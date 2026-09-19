# Curbox agent guide

Curbox is an Android screen time manager. One accessibility service blocks apps, short videos, keywords, and selected UI elements.

This file applies to the whole repository. Preserve more specific instructions if a nested `AGENTS.md` is added later.

## Start here

- Modules: `:app` and `:apitester` (sample client for Curbox AIDL API).
- Language and UI: Kotlin, classic Views, Fragments, and ViewBinding.
- Do not add Jetpack Compose or the Navigation component.
- JVM target: 17.
- Package root: `app/src/main/java/neth/iecal/curbox`.
- Prefer the smallest change that follows the nearest existing implementation.
- Readability is more important than cleverness. Follow the style of each file you touch.
- Ask before making an architectural change or updating Room entities (migrations are destructive).
- Preserve unrelated work in the repository. Do not rewrite nearby code unless the task requires it.
- Add comments only when they explain behavior the code itself cannot express.

## Working sequence

1. Read the affected class and the closest similar feature before editing.
2. Trace all relevant process, flavor, persistence, and lifecycle boundaries.
3. Make the narrowest complete change. Reuse current patterns and dependencies.
4. Check every entry point, refresh path, cleanup path, and optional feature gate.
5. Run the most focused useful verification, then broader flavor builds when the change can affect them.
6. Report what changed, which checks ran, and any checks that could not run.

## Nonnegotiable invariants

### Accessibility service safety

`services/AppBlockerService.kt` must never crash because an individual feature fails.

- Keep feature calls inside the service's existing error containment boundaries.
- Log nonfatal failures through `CrashLogger.logNonFatalError` and swallow them.
- In coroutine workers, rethrow `CancellationException`; do not report it as a crash.
- Wire every new feature through its complete lifecycle: setup, receivers, event handling, and cleanup.

Never traverse accessibility node trees directly in `onAccessibilityEvent`.

- Cheap work such as app blocking, grayscale, focus mode, and lightweight tracking may run inline.
- Node walking belongs in the background worker fed by `Channel.CONFLATED`.
- Copy queued events with `AccessibilityEvent.obtain(event)`.
- Recycle every copied event in all paths, including failed sends, dropped events, exceptions, and shutdown.

### Multiple processes

The app runs in three processes:

| Process | Main component |
| --- | --- |
| Main | UI and `Curbox` application initialization guarded by `isMainProcess()` |
| `:app_blocker_service` | `AppBlockerService` |
| `:crash_handler` | `CrashLogActivity` |

Consequences:

- Access Room only through `AppDatabase.getInstance()` (enables multi-instance invalidation).
- Access settings only through `DataStoreManager` in `utils/DataStore.kt` (`MultiProcessDataStoreFactory` singleton owner). Never create a second DataStore for the same file.
- Do not assume initialization guarded by `Curbox.isMainProcess()` ran in the service process.
- UI to service configuration updates use broadcasts unless that feature already collects the settings flow. Follow the feature's existing pattern.

### Settings compatibility and delay

`data/models/Settings.kt` is serialized as JSON with Gson.

- Every field must have a default value so older stored JSON still loads. Do not rename fields casually.
- Add a setting through `Settings`, an `updateX()` method in `DataStoreManager`, the UI, and the feature's refresh path.
- Restriction settings are gated by the settings change delay (`PendingSettingsChange`). Weakening a restriction is delayed; stricter changes apply immediately. For every new gated field, update all of:
  1. `GatedSettingsField`
  2. The `withFieldValue` branch
  3. `utils/RestrictionComparator.kt`
  4. The label in `ui/fragments/main/reducers/advanced/SettingsChangeDelayFragment.kt`

### Room data

Room uses `fallbackToDestructiveMigration()`. Any entity change requires a database version bump in `data/db/AppDatabase.kt` and wipes local user data on upgrade. Get direction before implementing a schema change.

### Build variants

Gate optional behavior at every entry point (UI, service lifecycle, manifests, API responses).

| Flavor | Sync | UI hider | Anti uninstall | Internet |
| --- | --- | --- | --- | --- |
| `full` | Yes | Yes | Yes | Yes |
| `playstore` | Yes | No | No | Yes |
| `fdroid` | No | Yes | Yes | No |

Relevant flags: `SUPPORTS_UI_HIDER`, `SUPPORTS_ANTI_UNINSTALL`, `SUPPORTS_WRITE_SECURE_SETTINGS`, `FDROID_VARIANT`, `SYNC_USE_FCM`. The Play Store manifest removes `AdminReceiver` and `NodePickerService`.

## UI and product copy

- Use ViewBinding and existing Fragment transactions.
- Use Material defaults and dynamic colors. Keep the visual style calm, minimal, and easy to scan.
- Coolvetica is for strong onboarding typography. Inter is the normal app font.
- All user-facing text belongs in `app/src/main/res/values/strings.xml`. Follow the existing localization pattern.
- Write for a nontechnical reader at about a sixth grade level. Short, concrete sentences.
- Do not use hyphens, en dashes, or em dashes in user-facing copy.

## Build and verification

Use the check that matches the change. Documentation-only changes do not need a Gradle build.

```bash
./gradlew testFullDebugUnitTest
./gradlew assembleFullDebug
./gradlew assemblePlaystoreDebug
./gradlew assembleFdroidDebug
./gradlew installAndGrantAccessibilityFullDebug
```

- Build all three flavors after changing shared source, source sets, manifests, BuildConfig gates, or optional feature wiring.
- `testFullDebugUnitTest` includes `CryptoBoxTest` and UI hider `ScriptLanguageTest`.
- Use the install task only when an emulator or device is available (installs, grants accessibility through adb, and launches Debug Curbox).
- Lint does not abort builds; inspect relevant warnings rather than treating a successful build as proof that lint is clean.
- Debug builds use the `.debug` application ID suffix and the name `Debug Curbox`.
- If a relevant check cannot run, say why in the handoff.

### Windows and PowerShell environment

```powershell
$env:JAVA_HOME = 'C:\Users\DELL\.jdks\jbr-21.0.11'
& "$env:JAVA_HOME\bin\java.exe" -version
.\gradlew.bat testFullDebugUnitTest
```

- Run commands directly in the shell. Do not wrap commands in `powershell -Command "<command>"`.
- If a path contains whitespace, quote the path and invoke it with `&` (e.g. `& "$env:JAVA_HOME\bin\java.exe" -version`).
- On Windows PowerShell, execute Gradle tasks using `.\gradlew.bat <task>`.

## Context-specific guides

Consult these targeted guides when working in corresponding areas:

| Situation | Guide |
| --- | --- |
| Adding a blocker/tracker, adding app or browser mod support, or updating the database | [`docs/recipes.md`](docs/recipes.md) |
| Navigating feature subsystems, service protection, or warning/approval screens | [`docs/architecture/features.md`](docs/architecture/features.md) |
| App rule evaluation, enforcement pipelines, or domain specifications | [`docs/architecture/app-rule-enforcement.md`](docs/architecture/app-rule-enforcement.md) and [`docs/spec/`](docs/spec/) |
| Understanding system design choices, trade-offs, or historical rationale | [`docs/adr/`](docs/adr/) |
| Working on multi-device sync, encryption, Supabase REST, Firebase/FCM, or cleanup | [`docs/sync.md`](docs/sync.md) |
| Building release APKs, verifying signatures, or publishing artifacts to GitHub | [`docs/release.md`](docs/release.md) |
| Running or authoring ADB-based device automation and Pester tests | [`tools/AGENTS.md`](tools/AGENTS.md) |
| Integrating with or modifying the exported AIDL service | [`docs/CURBOX_API.md`](docs/CURBOX_API.md) |
| Contributor workflows, translations, and repository conventions | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
