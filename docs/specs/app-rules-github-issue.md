## Problem Statement

보호자는 여러 앱의 실제 전면 사용시간을 함께 제한하고, 다른 앱을 사용한 만큼 허용시간을 적립하며, 특정 시간대에는 태블릿 사용을 막고 싶다. 현재 Curbox의 앱 그룹은 앱 목록과 제한 설정을 한 객체에 결합하고, 현재 활성 시간 구간만 기준으로 사용량을 계산한다. 이 구조에서는 하나의 앱 묶음을 여러 규칙에서 재사용하거나, 모든 앱에서 일부 그룹을 제외하거나, 여러 활성 구간에서 하나의 일일 허용량을 나누어 쓰거나, 분할 화면과 임의 시각의 건너뛰기를 정확히 계산하기 어렵다. 현재 전면 추적은 Curbox나 System UI를 열어도 이전 앱 세션이 계속될 수 있고, 현재 시간대 사용량을 비례 계산해 적게 집계하며, 앱 전환 시 직전 앱 사용량이 규칙 판정보다 늦게 반영되는 문제도 있다.

## Solution

앱 그룹을 재사용 가능한 중립 앱 묶음으로 만들고, 요일, 시간 구간, 적용 범위, 사용 가능 시간, 사용 조건과 적립 정책을 별도의 앱 규칙으로 정의한다. 모든 규칙은 같은 판정 모델을 사용하며 사용 가능 시간 0으로 시간 구간 잠금도 표현한다. 앱별 전면 세션을 현재 사용일 동안 Room에 저장해 임의 구간, 분할 화면, 건너뛰기와 앱 그룹의 소급 적용을 정확히 계산한다. 보호자는 규칙별로 실제 사용시간을 더하거나 다음 복원 시각 이내까지 규칙을 건너뛸 수 있다. 앱 또는 앱 그룹의 오늘 사용시간과 실행 횟수를 별도 기능으로 초기화할 수 있다.

## User Stories

1. As a 보호자, I want to create a neutral 앱 그룹, so that I can reuse the same app set in several rules.
2. As a 보호자, I want an 앱 그룹 to contain only foreground launchable apps, so that irrelevant system components are not shown in the picker.
3. As a 보호자, I want an app to belong to several 앱 그룹, so that I can organize apps for different purposes.
4. As a 보호자, I want to include all apps in an 앱 규칙, so that newly installed launchable apps are covered automatically.
5. As a 보호자, I want to include several 앱 그룹 in one rule, so that their app sets are combined.
6. As a 보호자, I want to exclude one or more 앱 그룹 from a rule, so that exceptions can be expressed without copying app lists.
7. As a 보호자, I want exclusion to win over inclusion, so that the final 적용 범위 is predictable.
8. As a 보호자, I want Curbox, the launcher, System UI, and the active keyboard to remain internally usable, so that the blocker can operate and the device can be navigated safely.
9. As a 보호자, I want Android Settings to remain selectable, so that I can choose whether a rule restricts it.
10. As a 보호자, I want to select weekdays for every rule, so that restrictions match the intended days.
11. As a 보호자, I want to enter start and end times, so that I can define arbitrary active ranges.
12. As a 보호자, I want an equal start and end time to mean 24 hours, so that a full day can be represented without a separate day option.
13. As a 보호자, I want overnight time ranges, so that a rule can continue into the next calendar day.
14. As a 보호자, I want an overnight range to belong to its start weekday, so that weekday behavior is unambiguous.
15. As a 보호자, I want overlapping ranges to behave as their union, so that overlap never double counts usage.
16. As a 보호자, I want several active ranges to share one daily allowance, so that the allowance can be divided across the day.
17. As a 보호자, I want to enter the 사용 가능 시간 for each rule, so that there is no hidden global default.
18. As a 보호자, I want a 사용 가능 시간 of zero to block the 적용 범위 during active ranges, so that time based locks use the same rule model.
19. As a 보호자, I want target use outside active ranges to remain in global statistics but not consume the rule allowance, so that statistics and enforcement have clear meanings.
20. As a 보호자, I want every applicable rule to allow an app before it opens, so that one exhausted rule cannot be bypassed by another rule.
21. As a 보호자, I want to require a minimum amount of 기여 앱 그룹 usage, so that target apps stay unavailable until the condition is met.
22. As a 보호자, I want the usage condition and earned allowance to be independent options, so that I can use either or both.
23. As a 보호자, I want several 기여 앱 그룹 to be combined as a package union, so that an overlapping app is counted once within a rule.
24. As a 보호자, I want earned allowance to use a one to one ratio without a daily cap, so that the policy is simple and transparent.
25. As a 보호자, I want all contributor time to become available after the threshold is met, so that reaching the threshold unlocks the configured allowance and gross earned time.
26. As a 보호자, I want contributor usage to count across the whole current 사용일, so that earning does not depend on the target rule's active ranges.
27. As a 보호자, I want the same contributor group to credit several rules independently, so that earned time is not a shared token pool.
28. As a 보호자, I want self references, cycles, and target contributor overlap to remain allowed, so that the code stays simple and I remain responsible for configuration quality.
29. As a 보호자, I want a rule with a missing contributor group to fail closed, so that deleting a dependency cannot weaken the restriction.
30. As a 보호자, I want the 사용일 to reset at a configurable local time with 04:00 as the default, so that late night use belongs to the intended day.
31. As a 보호자, I want changing the reset time to start a new 사용일 immediately, so that the new boundary has a clear effect.
32. As a 보호자, I want simultaneously visible split screen apps to accrue time independently, so that all visible app use is accounted for.
33. As a 보호자, I want Curbox and System UI windows excluded without ending other apps that remain visible, so that only actually visible use is charged.
34. As a 보호자, I want an app transition to flush the previous usage before evaluating the new app, so that earned and consumed time are current.
35. As a 보호자, I want to add actual foreground allowance to one rule, so that temporary permission is consumed only while covered apps are used.
36. As a 보호자, I want repeated grants to the same rule to accumulate, so that I can extend access in several steps.
37. As a 보호자, I want added time to be shared across all active ranges and expire at the next reset, so that it follows the rule's current 사용일.
38. As a 보호자, I want to skip one rule until a chosen time, so that other applicable rules continue to protect the app.
39. As a 보호자, I want the chosen skip end to be no later than the next reset, so that temporary bypass cannot silently cross into another 사용일.
40. As a 보호자, I want a quick option to skip one rule until the next reset, so that I do not need to enter the boundary manually.
41. As a 보호자, I want skipped usage to remain in global statistics but not consume the skipped rule, so that the exception is scoped precisely.
42. As a 보호자, I want to apply an 앱 그룹 edit from now, so that prior usage keeps its previous membership meaning.
43. As a 보호자, I want to apply an 앱 그룹 edit from the start of today, so that current 사용일 records are recomputed using the new membership.
44. As a 보호자, I want retroactive target membership to count only sessions inside that rule's active ranges, so that its daily consumption remains accurate.
45. As a 보호자, I want retroactive contributor membership to use the whole current 사용일, so that earned time follows contributor semantics.
46. As a 보호자, I want to reset one app's today usage and launch count, so that I can forgive its current usage without changing rules.
47. As a 보호자, I want to reset an 앱 그룹, so that every current member app is reset globally.
48. As a 보호자, I want a reset to affect statistics, target consumption, and contributor calculations together, so that there is one authoritative app usage value.
49. As a 보호자, I want the last used timestamp to survive a usage reset, so that recency information remains available.
50. As a 보호자, I want a foreground app to start accumulating again immediately after reset, so that only past usage is forgiven.
51. As a 보호자, I want launch count to remain a statistic rather than a lock condition, so that this change remains time based.
52. As a 보호자, I want a separate guardian password, so that Curbox management access is independent of the legacy uninstall password.
53. As a 보호자, I want Curbox to require authentication whenever I return to its management UI, so that leaving and returning does not reuse authorization.
54. As a 보호자, I want rule grants, skips, and usage resets to require fresh authentication when a password exists, so that weakening actions are deliberate.
55. As a 보호자, I want those actions to remain available without authentication when no password is configured, so that password setup stays optional.
56. As a 보호자, I want no password length or retry limit imposed by this feature, so that I remain responsible for credential strength.
57. As a tablet owner, I want Curbox to avoid Device Owner provisioning, so that removal does not create a normal recovery path that may end in factory reset.
58. As a tablet owner, I want best effort Accessibility, overlay, and ordinary Device Admin protection, so that deletion and setting changes are discouraged without claiming kiosk grade enforcement.
59. As a 보호자, I want PiP handling left to Android system settings, so that this change does not depend on unsupported Device Owner or shell policies.
60. As a 보호자, I want the enforcement ledger to keep restrictions working when statistics tracking is off without adding that period to history, so that a display preference cannot bypass a rule or violate the tracking preference.
61. As a 보호자, I want a lock screen to identify every denying rule and let me approve one rule at a time, so that other protections remain effective.
62. As a 보호자, I want guardian extra time to work even when a prerequisite is unmet or the direct allowance is zero, so that the granted amount has one consistent meaning.
63. As a 보호자, I want referenced target groups protected from accidental deletion, so that a rule never silently loses its target scope.
64. As a 보호자, I want a deleted contributor group to leave its dependent rule unavailable, so that deleting a dependency cannot weaken a restriction.

## Implementation Decisions

- Separate the neutral 앱 그룹 model from the 앱 규칙 model and migrate existing app group restrictions into the new structure. Use immutable identifiers that survive rename, issue new identifiers for create and copy, and make legacy migration deterministic or explicitly completion marked.
- Validate, delay, and apply 앱 그룹 and 앱 규칙 as one atomic restriction snapshot. Compare later edits with the currently applied snapshot and replace any pending candidate as a whole.
- Use one unified rule type. A time range lock is an ordinary rule with all apps in scope and zero 사용 가능 시간.
- Represent scope as dynamic all apps and group inclusions followed by group exclusions. Normalize identifiers and treat exclusions as authoritative.
- Keep required rule fields default compatible in serialized settings. Every new setting field must deserialize safely from older JSON.
- Preserve the existing settings ownership and multi process boundaries. Runtime services read settings through the existing settings manager rather than opening another data store.
- Use a single rule evaluation boundary that accepts the current use day, normalized active intervals, resolved target packages, resolved contributor packages, observed sessions, guardian extra time, and skip state. It returns whether the rule currently allows the app and the values needed for user facing status.
- Resolve all applicable rules for the foreground package and block when any rule denies it.
- Calculate target consumption from the intersection of visible foreground sessions, target scope, active rule ranges, and non skipped periods.
- Calculate contributor usage from the union of contributor packages across the whole current 사용일. Do not recurse through other rules.
- Store a short lived per app foreground session and launch event ledger in Room in addition to aggregate usage. The ledger is authoritative for the current use day, while calendar day aggregates remain derived history, sync, and display data. Detailed records are removed once they are no longer needed for current use day calculations.
- When statistics tracking is disabled but active time based rules exist, keep only the temporary enforcement ledger needed by those rules. Do not merge that period into history or sync, do not restore it retroactively when tracking is enabled, and stop recording when neither statistics nor rules need it.
- Bump the Room schema version. Destructive migration and loss of existing Room usage data are explicitly accepted because the target installation has no existing usage data.
- Track every simultaneously interactive application window as an independent session. Split and persist sessions at app visibility changes, reset boundaries, usage resets, skip boundaries, and service shutdown where possible.
- Flush previous visible sessions through the serialized usage writer before checking a newly foregrounded app.
- Reconcile the complete set of visible application packages on each relevant event. Exclude Curbox, System UI, IME, overlay, and non application windows, deduplicate multiple windows from the same package, and close only packages that are no longer visible.
- Replace current hour proration for current use day enforcement with exact session intersection. Aggregates may remain for historical display and sync compatibility.
- Treat app group edit modes as prospective membership or current use day recomputation. Persist enough membership effective time information to avoid incorrectly applying a prospective edit to earlier sessions. For delayed changes, interpret now and today at the actual due application time rather than the request time.
- Reject simple deletion of a group referenced by target inclusion or exclusion and require explicit reference removal or dependent rule deletion. Allow contributor group deletion but retain the broken identifier and deny the known target scope until repaired. Never replace a valid runtime snapshot with a corrupted snapshot.
- Store guardian extra time and rule skip state as local runtime state keyed by stable rule identifiers and the current use day. Do not model either as rule deactivation or the existing wall clock warning cooldown. Extra time is grantable before an active range and provides only the granted allowance even when prerequisites are unmet or direct allowance is zero.
- On the lock screen, show every currently denying rule, let the guardian choose one rule to grant or skip, then immediately reevaluate all applicable rules and remain locked while any denial remains.
- A rule skip pauses only that rule's consumption. Raw app sessions continue to be persisted and can still contribute to other rules.
- Usage reset computes the current use day time and launch count delta from the authoritative ledger, cuts or deletes the affected records, and subtracts only that delta from calendar aggregates in one Room transaction. It preserves previous use day data sharing the same calendar date and preserves last used time. Group reset resolves current members and resets them as one protected operation.
- App launch count remains tracked for statistics and reset but is not added to rule evaluation.
- Use a separate locally stored guardian credential verifier with a random salt and slow password derivation. Do not store plaintext or reuse the legacy uninstall password hash.
- Management authorization survives Curbox owned dialogs and configuration changes. A Curbox initiated single result system contract may preserve it while protected content is obscured, but returning from another app or task and unexpected focus loss require reauthentication.
- Do not add Device Owner or Lock Task behavior. Describe protection as best effort and keep removal possible through ordinary supported device administration cleanup.
- Reuse the existing classic Views, Fragment transactions, ViewBinding, Material time picker, weekday selection, and warning screen visual patterns. Do not add Compose or Navigation components.
- Reuse the start and end shape of existing time intervals, but preserve overnight intervals as one semantic interval, merge overlaps for evaluation, and interpret equal endpoints as 24 hours.
- User facing text uses the glossary terms 앱 그룹, 기여 앱 그룹, 앱 규칙, 적용 범위, 사용일, 사용 조건, 사용 가능 시간, 적립 허용량, 보호자 추가시간, 제한 건너뛰기, and 사용량 초기화.
- Shared source changes must keep full, playstore, and fdroid variants compiling even though the target distribution is personal and not Play Store.

## Testing Decisions

- Prefer one high level, deterministic rule evaluator test seam over tests that reach into individual helper methods. Tests provide rules, package membership, foreground sessions, use day boundaries, grants, and skips, then assert externally visible allow or block decisions and reported consumption.
- Extend the existing JVM test style used for time windows, schedule utilities, settings migrations, warning selection mapping, and restriction comparison.
- Test scope resolution for all apps, multiple inclusions, exclusions, dynamic future packages, duplicates, and required internal exemptions.
- Test normal, overnight, equal endpoint 24 hour, adjacent, overlapping, and multiple active intervals across weekday and use day boundaries.
- Test zero allowance, shared allowance across intervals, outside range tracking, and the rule combination behavior where one denial blocks.
- Test all four combinations of usage condition and earning, union deduplication, shared contributor groups across rules, unlimited one to one earning, overlap and cyclic configurations, and fail closed missing references.
- Test visible session accounting with one app, split screen apps, window focus changes, Curbox and System UI transitions, screen off, service restart, and a transition where contributor usage must be flushed before target evaluation.
- Test statistics tracking disabled with and without active rules, temporary enforcement recording, absence from history and sync, and no retroactive restoration.
- Test current use day reset at 04:00 and another configured time, reset time changes, overnight rules crossing the reset, and weekday ownership by the interval start day.
- Test guardian extra time consumption, accumulation, multiple active ranges, overlapping rules, grant expiry, and behavior outside active ranges.
- Test a lock screen with several denying rules, one rule approval, full reevaluation, unmet prerequisites, zero direct allowance, and pre granting outside an active range.
- Test per rule skip start and end, next reset maximum, skipped usage exclusion from only that rule, simultaneous rules with different skip states, and reset while skipped.
- Test group edits with both effective modes for target and contributor roles, including app additions, removals, overlapping groups, and current use day recomputation.
- Test target reference deletion protection, explicit cascade choices, contributor deletion fail closed behavior, corrupted snapshot rejection, and cold start invalid state.
- Test app and group usage reset for time and launch count delta subtraction, previous use day preservation on the same calendar date, last used preservation, overlapping groups, current foreground restart, and propagation into all target and contributor calculations.
- Test settings JSON migration from the existing combined app group model and default values for every new field.
- Test Room upgrade behavior on a clean target installation and ledger cleanup after the relevant current use day expires.
- Add focused instrumentation coverage for interactive window enumeration and split screen because Accessibility window behavior cannot be proven by pure JVM tests.
- Verify app blocker service error containment by injecting evaluator or persistence failures and asserting the service continues handling later events without crashing.
- Run unit tests and assemble all three flavors after shared model, Room, manifest, service, or UI changes.
- Tests assert user visible decisions and persisted usage outcomes, not private method call order or exact class layout.

## Out of Scope

- Removing, redesigning, or hardening the exported Curbox API.
- Changing encrypted synchronization behavior or remote mutation policy.
- Hardening Android Auto Backup, the app ZIP restore path, exported broadcasts, NFC triggers, or other external mutation entry points.
- Controlling app installation.
- Automatically disabling or ejecting Picture in Picture.
- Device Owner provisioning, fully managed device setup, Lock Task mode, or kiosk grade guarantees.
- Adding app launch count as a restriction condition.
- A global action that skips all rules at once.
- A shared earned time or guardian extra time pool across rules.
- Historical immutable app group membership reports beyond the current use day recomputation needs.

## Further Notes

- The default use day reset is 04:00 local time, but the setting is global and configurable.
- A conventional 22:00 to 06:00 tablet lock is configured as an ordinary rule with all apps included and zero 사용 가능 시간.
- Device Owner was intentionally rejected because preserve data deprovision is not guaranteed across devices and factory reset can remain the last recovery method.
- Accessibility based protection is inherently best effort. Safe mode, force stop, OEM system surfaces, and physical recovery cannot be represented as impossible.
- PiP must be disabled by the 보호자 in Android's per app system settings when needed.
- Existing Room data loss from the version bump is accepted for this installation. This approval does not remove the need to call out destructive migration in release notes.
- The issue should receive only the `ready-for-agent` triage label.
