# Common change recipes

Recipes and checklists for frequent development workflows in Curbox.

## Add a blocker or tracker

1. Add a plain class in `blockers/` or `trackers/` based on the closest feature.
2. Declare refresh actions as companion constants (e.g. `INTENT_ACTION_REFRESH_*`).
3. Wire the full lifecycle in `services/AppBlockerService.kt` (instantiate, setup in `onServiceConnected()`, register receivers, cleanup in `onDestroy()`).
4. Keep node traversal in the background worker via `Channel.CONFLATED`. Inline only cheap checks in `onAccessibilityEvent`.
5. Add defaulted configuration to `data/models/Settings.kt` and an updater method to `utils/DataStore.kt` (`DataStoreManager`).
6. Add delay gating if the setting controls restriction strength:
   - `GatedSettingsField`
   - `withFieldValue` branch
   - `utils/RestrictionComparator.kt`
   - UI label in `ui/fragments/main/reducers/advanced/SettingsChangeDelayFragment.kt`
7. Wire UI and broadcast refresh behavior.
8. Apply every relevant flavor flag (`SUPPORTS_UI_HIDER`, `SUPPORTS_ANTI_UNINSTALL`, `SUPPORTS_WRITE_SECURE_SETTINGS`, etc.).

## Add a browser or app mod

- **Browser URL bar support**: Edit `hardcoded/BrowserUrlBarIds.kt`. Copy the closest entry (e.g. Chrome or Firefox) and update `packageName` and `displayUrlBarId`.
- **App mods (Instagram/YouTube/Facebook)**: Edit `hardcoded/ReelAppConfig.kt`. Copy the original app configuration and replace the package name key with the mod package name.
- See `CONTRIBUTING.md` for additional contributor guidance.

## Change the database

Room uses `fallbackToDestructiveMigration()`. Any entity or schema change wipes local user data on upgrade.

1. Update the `@Entity` and corresponding DAO in `data/db/`.
2. Register the updated entity and increment `DATABASE_VERSION` in `data/db/AppDatabase.kt`.
3. Inform the user about the destructive migration tradeoff before implementing.
