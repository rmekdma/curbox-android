# 03 — App Group Edit Immediate Service Synchronization

**What to build:** Instant reflection of app group member edits in active rule evaluation and background service blocking without requiring service restart or manual re-toggling.

**Blocked by:** None — can start immediately

**Status:** done

- [x] Verify and ensure the app group editor dispatches a refresh broadcast to the blocker service process upon saving changes.
- [x] Update the AppRuleBlocker broadcast receiver in the service process to immediately reload the latest AppRuleSnapshot from DataStore.
- [x] Ensure the runtime package resolver updates target and contributor package sets in memory right away when AppGroupEditMode is NOW.
- [x] Verify that adding or removing a package in an app group immediately updates blocking and usage tracking behavior for that package.
