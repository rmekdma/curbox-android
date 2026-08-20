# 02 — Guardian Session Foreground Lifecycle

**What to build:** A seamless guardian authentication lifecycle that keeps the session authenticated while the user navigates between any Curbox screens and completes one-shot system tasks, but reliably invalidates the session the moment Curbox leaves the foreground.

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] Update Guardian session tracking to observe overall application foreground state across all Curbox activities.
- [ ] Retain active authenticated state during internal navigation across tabs, sub-fragments, and child activities (such as package pickers).
- [ ] Maintain session preservation when returning from designated one-shot external activities (QR barcode scanner, document picker for CSV export, system permission guides).
- [ ] Invalidate authentication state immediately when Curbox transitions to the background (all activities stopped without a pending one-shot system token).
- [ ] Verify that opening Curbox from launcher/recents after backgrounding requires guardian password input, but internal navigation does not prompt repeatedly.

