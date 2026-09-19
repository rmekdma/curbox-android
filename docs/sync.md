# Sync and backend architecture

Architecture and invariants for Curbox multi-device sync, encryption, and cleanup.

## End-to-end encryption

- End-to-end encryption uses `CryptoBox` (`data/crypto/CryptoBox.kt`).
- Covered by unit tests in `CryptoBoxTest`.

## Backend services

- **Supabase**: REST client implemented directly using OkHttp (`SupabaseRest`). There is no Supabase Android SDK.
- **Firebase / FCM**: Initialized manually from `FcmConfig`. There is no Google Services Gradle plugin or `google-services.json`.
- **Backend migrations**: Cleanup SQL lives in `supabase/migrations/` and is applied separately on the backend, not by the Android build.

## Usage data syncing and cleanup

- Remote usage is merged through `remoteAppUsage()` and `remoteWebsiteUsage()`. Synced websites use `SYNCED_WEB_PACKAGE`.
- Local historical data is purged by `utils/UsageStatsCleaner.kt`.

## Build flavor integration

| Flavor | Sync source set | Notes |
| --- | --- | --- |
| `full` | `app/src/sync/java` | Real sync implementation with Supabase and FCM. |
| `playstore` | `app/src/sync/java` | Real sync implementation. Billing is currently a free stub (`SyncEntitlement`). |
| `fdroid` | `app/src/fdroid/java` | Stubbed with `NoopSyncProvider`. Must compile without sync-only classes, Firebase, Supabase, or `INTERNET` permission. |

- Real sync code lives exclusively in `app/src/sync/java`. Common interfaces and `SyncGateway` live in `app/src/main/java`.
- `SYNC_USE_FCM` in `app/build.gradle.kts` controls the FCM push migration in sync-enabled flavors.
- Sync initializes only in the main application process (`Curbox.isMainProcess()`).
