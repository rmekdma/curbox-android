# 01 — Opaque Fullscreen Warning Screen

**What to build:** An opaque, solid-background full-screen warning screen for locked apps that completely obscures the underlying application and presents challenges and unlock options in a clear, scrollable, centered layout.

**Blocked by:** None — can start immediately

**Status:** done

- [x] Remove translucent window attributes and set an opaque surface theme for WarningActivity.
- [x] Refactor the warning overlay layout into a full-screen layout with an Edge-to-Edge solid background and a scrollable centered card/container.
- [x] Ensure challenge controls (math problem input, typing quote and challenge input, intent reason input, timer picker, countdowns) and action buttons (Proceed, Cancel) remain visible, centered, and easily scrollable when the soft keyboard is displayed.
- [x] Verify that underlying apps cannot be seen through the warning activity background on both light and dark themes.
