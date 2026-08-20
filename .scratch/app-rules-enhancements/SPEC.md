# App Rules & UX Enhancements Specification

## Problem Statement

Users and guardians using Curbox encounter several usability and functional friction points:
1. When an application is locked by a restriction rule, the warning screen appears over the locked app with a transparent background, causing the warning text, time status, and challenge inputs to overlap unreadably with the underlying app visuals.
2. When a guardian password is set, navigating between different screens within the Curbox app repeatedly prompts for the password instead of remembering the authenticated session while the app remains open in the foreground.
3. Editing the list of packages in an app group does not immediately take effect on the rules referencing that group in the blocking service.
4. The ongoing foreground notification only shows generic service status rather than informative, per-rule usage progress, allowed time, and guardian added time.
5. Usage conditions for app rules currently support only a single flat time applied to contributors, lacking the flexibility to require total combined time, per-group specific minimum times, or a mix of both.

## Solution

1. Provide an opaque, full-screen background for the warning activity with centered scrolling challenge content, guaranteeing clean readability and preventing visual interaction with the underlying application.
2. Establish a foreground session management policy for guardian authentication that keeps the session alive across internal screen transitions and one-shot system tasks (such as QR scanning and file export), but invalidates the session the moment Curbox leaves the foreground.
3. Synchronize app group membership modifications immediately with the blocking service, refreshing snapshot state and package scope without delay.
4. Deliver fixed, live status updates in the ongoing service notification displaying `[규칙명] X분 사용 / Y분 허용 (추가 Z분 포함)` in a one-line summary and expanded multi-rule list, refreshed every minute and on application transitions.
5. Enhance usage condition data modeling and rule evaluation to support total combined contributor minutes, individual contributor group minutes, or both simultaneously with an intuitive form UI.

## User Stories

1. As a user whose app is locked, I want a solid, non-transparent warning screen so that I can clearly read the lock reason and challenge prompt without visual clutter from the background app.
2. As a user completing a challenge on the warning screen, I want the input field and text to stay legible and comfortably centered regardless of keyboard visibility or screen orientation.
3. As a guardian configuring settings, I want to authenticate once when opening Curbox and navigate freely across all tabs and sub-screens without being repeatedly interrupted by password prompts.
4. As a guardian exporting usage data or scanning a QR code, I want my authenticated session preserved when returning from the system document picker or scanner.
5. As a guardian who finishes using Curbox and switches to another app or home, I want the management session to lock immediately so unauthorized users cannot access settings.
6. As a guardian updating an app group's member apps, I want the changes to immediately take effect in active restriction rules without needing a service restart.
7. As a guardian checking daily progress, I want the notification bar to show real-time used time, total allowed time, and guardian added time for each active rule at a glance.
8. As a guardian with multiple active rules, I want to expand the notification to see the individual breakdown for all active rules formatted consistently as `[규칙명] X분 사용 / Y분 허용 (추가 Z분 포함)`.
9. As a guardian configuring a study rule, I want to require that a child spend at least 30 minutes total across educational apps before gaming apps unlock.
10. As a guardian configuring a balanced routine, I want to require at least 20 minutes in a reading app AND at least 15 minutes in a math app before recreational apps unlock.
11. As a guardian configuring advanced conditions, I want to leave total minutes or specific group inputs blank when only specific individual thresholds or total thresholds are desired.

## Implementation Decisions

### Warning Screen Presentation
- Transform the warning activity from a transparent dialog overlay into a solid, opaque surface screen using the app surface color palette.
- Implement an Edge-to-Edge root layout containing a scrollable container with centered content, ensuring that challenge components (math, typing, reason input, timer) and action buttons remain fully visible and responsive when the soft keyboard is raised.

### Guardian Session Lifecycle
- Track foreground application presence across Curbox activities.
- Retain the authenticated guardian session in memory while any Curbox activity is running in the foreground.
- Continue supporting one-shot external system operations (file pickers, barcode capture, system permission guides) without invalidating the active session upon return.
- Invalidate the authenticated session immediately when the entire Curbox application transitions to the background (all activities stopped and no pending one-shot system handoff).

### App Group & Rule Synchronization
- Ensure that app group modifications dispatch a refresh signal to the blocker service process.
- The blocker service reloads the latest snapshot from persistence and updates the active evaluation scope immediately so that added or removed packages are respected in real-time rule checks.

### Live Rule Notification
- Update the blocking foreground service notification to display active rule metrics in the format: `[규칙명] X분 사용 / Y분 허용 (추가 Z분 포함)` where total allowed time includes guardian-granted extra minutes, and the extra minutes note is included only when extra time is greater than zero.
- Use a compact single-rule summary for the collapsed notification view and an expanded multi-rule listing (`BigTextStyle`) when multiple rules are active.
- Refresh notification content every minute via a lightweight periodic timer and on application foreground change events.

### Usage Condition Total & Per-Group Requirements
- Extend the rule configuration model to support an optional total required usage condition duration across all contributor groups, as well as a mapping of specific contributor group IDs to individual required durations.
- Maintain backwards compatibility by defaulting missing fields during deserialization.
- Update the pure rule evaluator to verify that total contributor usage meets the total threshold (if specified > 0) AND each contributor group with a specified individual threshold meets its requirement before marking the condition as met.
- Provide a clear, unified UI in the rule editor: a total condition time input and a list of selected contributor groups with individual condition time inputs, treating blank/zero fields as unconstrained.

## Testing Decisions

- **Warning Screen UI**: Verify that the activity renders with full opacity and properly accommodates dynamic content and software keyboard insets.
- **Guardian Session**: Test unit and instrumentation transitions ensuring intra-app navigation maintains authentication, external system returns preserve auth, and backgrounding terminates auth.
- **Rule Synchronization**: Test that updating group packages immediately changes the evaluated target/contributor package sets in the evaluator and blocker service.
- **Notification Formatting**: Test string generation across multiple rule combinations, zero vs non-zero added times, and multi-rule expansions.
- **Usage Condition Evaluator**: Unit test all matrix combinations (total-only, per-group-only, combined total and per-group, zero conditions, missing contributors, and earned allowance interaction).

## Out of Scope

- Introducing new database entities or Room schema changes (all configurations remain in settings DataStore).
- Changing Play Store / F-Droid flavor capability splits.
- Adding third-party UI framework dependencies such as Jetpack Compose.

## Further Notes

- All user-facing strings must be localized and stored in `strings.xml` without dashes or hyphens per project UX guidelines.

