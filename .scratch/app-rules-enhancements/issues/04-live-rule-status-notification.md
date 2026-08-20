# 04 — Live Rule Status Notification in Foreground Service

**What to build:** A live, informative foreground service notification displaying per-rule used time, allowed time, and guardian added time in a structured format: `[규칙명] X분 사용 / Y분 허용 (추가 Z분 포함)`.

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] Calculate current usage and effective allowance (including guardian grants) per active rule in BaseBlockingService / AppBlockerService.
- [ ] Format notification text following the spec: `[규칙명] X분 사용 / Y분 허용` (and append `(추가 Z분 포함)` when guardian extra minutes Z > 0).
- [ ] Support a collapsed summary for the notification title/text and an expanded BigTextStyle multi-line view displaying all active rules when multiple rules exist.
- [ ] Implement an efficient 1-minute periodic tick and event-driven refresh to keep notification metrics accurate without excessive battery or NotificationManager overhead.
- [ ] Handle edge cases such as rules with no time limit (schedule-only) and localized strings adhering to project copy guidelines (no hyphens/dashes).

