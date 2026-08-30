# 잠금 화면 보호자 추가시간 총량 입력

## Problem Statement

앱 규칙 잠금 화면의 보호자는 현재 한 번에 새로 지급할 분만 입력할 수 있다. 이미 같은 규칙에 지급한 보호자 추가시간이 있어도 현재 총량을 보면서 목표 총량으로 늘릴 수 없어서, 기존 지급량을 기억하고 차이를 직접 계산해야 한다.

보호자는 `10분을 더 지급한다`와 `오늘 총 40분이 되게 한다`를 같은 결과를 만드는 두 가지 입력 방식으로 사용하고 싶다. 이 기능은 선택한 앱 규칙의 현재 사용일에만 적용되어야 하며, 기존 지급 기록과 보호자 인증 경계를 유지해야 한다.

## Solution

앱 규칙 잠금 화면의 `추가 시간` 동작을 `추가 시간 변경`으로 바꾼다. 팝업은 선택한 규칙에 현재 사용일까지 지급한 보호자 추가시간 총량을 보여주고, 다음 두 입력을 함께 제공한다.

- `추가 시간`: 현재 총량에 새로 더할 양
- `총 추가 시간`: 적용 후 지급한 보호자 추가시간 총량

두 입력은 양방향으로 연결한다. 보호자가 한 입력을 바꾸면 다른 입력이 즉시 다시 계산된다. 잠금 화면에서는 총량 증가만 허용한다. 저장할 때는 기존 지급 기록을 보존하고, 현재 총량과 목표 총량의 차이만 새 보호자 추가시간으로 지급한다.

## User Stories

1. As a 보호자, I want to see the currently granted guardian extra time total for the selected rule, so that I do not have to remember earlier grants.
2. As a 보호자, I want the displayed total to apply only to the selected 앱 규칙, so that grants for another rule do not affect my decision.
3. As a 보호자, I want the displayed total to apply only to the current 사용일, so that expired grants from an earlier 사용일 are not included.
4. As a 보호자, I want to enter the number of minutes to add, so that the familiar incremental grant workflow remains available.
5. As a 보호자, I want entering `10` as 추가 시간 to increase a current total of `30` to `40`, so that the result is visible before I apply it.
6. As a 보호자, I want to enter the desired 총 추가 시간 directly, so that I can choose a round target without calculating the difference myself.
7. As a 보호자, I want entering `45` as 총 추가 시간 when the current total is `30` to calculate `15` as 추가 시간, so that both inputs describe the same change.
8. As a 보호자, I want positive values to appear without a leading plus sign, so that the form stays calm and easy to scan.
9. As a 보호자, I want the current total to remain visible outside the input fields, so that clearing a field does not remove my comparison point.
10. As a 보호자, I want the 총 추가 시간 field to initially show the current total as its placeholder, so that the meaning of the field is immediately clear.
11. As a 보호자, I want the first tap on the initial 총 추가 시간 placeholder to give me an empty field, so that I can type a target without deleting the old value.
12. As a 보호자, I want a later tap on an automatically calculated value to select the whole value, so that I can replace it in one action.
13. As a 보호자, I want a total equal to the current total to be rejected, so that a no-op does not trigger authentication or create a grant.
14. As a 보호자, I want a total below the current total to be rejected, so that the lock screen cannot reduce or revoke a grant.
15. As a 보호자, I want the error `총 추가 시간은 현재 값보다 커야 해요.` after submitting an equal or smaller total, so that I know how to correct it.
16. As a 보호자, I want validation to happen when I press 적용 rather than while I am typing, so that partial input does not flash premature errors.
17. As a 보호자, I want 적용 to remain enabled while I edit, so that the form does not appear broken before explaining an invalid value.
18. As a 보호자, I want invalid submission to keep the popup open, so that I can correct the value without starting again.
19. As a 보호자, I want blank, zero, nonnumeric, and numeric overflow inputs to be rejected, so that only a positive representable grant is stored.
20. As a 보호자, I want no new product maximum for extra minutes, so that this change does not silently narrow the existing grant behavior.
21. As a 보호자, I want the normal guardian authentication step to remain required, so that changing the input style does not weaken approval security.
22. As a 보호자, I want a wrong or cancelled password to leave the rule unchanged, so that an unauthenticated grant is never stored.
23. As a 보호자, I want earlier grant records to remain intact, so that grant time semantics and already consumed time remain correct.
24. As a 보호자, I want only the positive difference between the current total and target total to be appended, so that the resulting total matches the preview.
25. As a 보호자, I want repeated changes to accumulate for the same rule and 사용일, so that each later popup starts from the new total.
26. As a 보호자, I want another denying rule to remain denied after approving one rule, so that this UI change does not weaken multi rule enforcement.
27. As a 보호자, I want the target app to be relaunched only after a valid authenticated grant is stored, so that the existing approval flow remains intact.
28. As a 보호자, I want the popup to use the existing calm Material visual style, so that it feels like part of Curbox rather than a separate tool.
29. As a 보호자, I want all displayed copy to use short concrete sentences, so that the form is understandable without technical knowledge.

## Implementation Decisions

- Modify only the internal 앱 규칙 guardian approval flow. The legacy warning cooldown screen and its temporary unlock behavior remain unchanged.
- Keep grants scoped by stable rule identifier, current 사용일, and current use day generation. Do not introduce a shared grant pool across rules.
- Rename the lock screen action and popup title to `추가 시간 변경`.
- Use the labels `추가 시간` and `총 추가 시간`. The domain meaning remains 보호자 추가시간; the shorter labels are local to this focused popup.
- Show `현재 총 추가 시간 N분` above the inputs. Do not show used or remaining guardian time in this change because normal quota exhaustion reaches the lock screen with no remaining grant.
- Replace the one programmatically created numeric field with a small dedicated classic Views dialog layout using Material text fields and ViewBinding. Do not add Compose or Navigation components.
- Model the form as one pure input state with the current total and one active source field. The state produces the raw or parsed 추가 시간, raw or parsed 총 추가 시간, submission validity, and user facing validation result.
- When 추가 시간 is the source, calculate `총 추가 시간 = 현재 총 추가 시간 + 추가 시간` with overflow safe arithmetic.
- When 총 추가 시간 is the source and it is greater than the current total, calculate `추가 시간 = 총 추가 시간 - 현재 총 추가 시간`.
- While a manually entered total is blank, unparsable, equal to, or below the current total, keep the derived 추가 시간 blank. Do not display a negative value.
- Do not prefix positive values with `+`. Negative inputs are unsupported.
- The initial 총 추가 시간 field is empty and uses the current total as its placeholder. Its first focus therefore presents an empty editable field. When a calculated or manually entered value is present, focusing that field selects all text.
- Keep 적용 enabled. Validate on submission, keep the dialog open on failure, and place the error on the relevant Material input rather than dismissing the dialog or relying only on a toast.
- Use `총 추가 시간은 현재 값보다 커야 해요.` for an equal or smaller target. Use the existing positive minute error semantics for blank, zero, malformed, or out of numeric range values.
- Preserve the existing no-maximum product behavior. Technical integer and duration overflow must fail safely without storing a grant or crashing the activity.
- Reuse the existing guardian password flow. Authentication begins only after the form passes validation.
- Preserve earlier grant ledger entries. Persist only the positive minute difference represented by the form through the existing owner DataStore transaction, then let the service observe the shared settings flow as it does today.
- Read the displayed current total from the normalized current-use-day override state for the selected rule. Ignore future grants, another rule's grants, an older 사용일, and an older reset generation.
- If the selected denial changes before opening the popup, load and display that newly selected rule's total. Do not cache one rule's total for another rule.
- Keep the existing success behavior: after a successful write, close the approval surface and relaunch the target package. Authentication failure, cancellation, validation failure, or write failure must not relaunch it.
- Do not change Room entities, the Room version, settings JSON fields, exported components, broadcasts, sync behavior, or flavor gates.
- Add or update user facing strings in the base resource set following the current guardian approval localization pattern. Do not introduce hyphens, en dashes, or em dashes in displayed copy.

### Tracer bullet delivery order

Each checkpoint must compile and leave a runnable vertical path. Finish and verify one checkpoint before loading the context needed for the next.

1. **Smallest executable slice:** add the pure form state and its focused JVM tests; add the dialog layout; show the current total for the selected rule; support typing 추가 시간, previewing 총 추가 시간, authenticating, and appending the positive difference through the existing grant writer. At this checkpoint the original happy path works end to end with the new total preview.
2. **Second input direction:** make 총 추가 시간 editable and derive 추가 시간 from it. Add the current-total placeholder and select-all focus behavior.
3. **Validation hardening:** keep the dialog open on submission errors; cover equal, smaller, zero, blank, malformed, and overflow inputs; confirm that no invalid state authenticates or writes.
4. **Integration hardening:** cover repeated grants, rule and 사용일 isolation, authentication outcomes, successful relaunch behavior, lifecycle cleanup, and service observation without changing those boundaries.
5. **Product finish:** finalize copy and Material spacing, update the focused requirements or spec documentation, then run broader flavor verification.

## Testing Decisions

- Prefer one new high-level pure form-state seam. Tests provide a current total plus user edits and assert the externally visible paired values and submission result. Do not test private text watchers or exact helper call order.
- Reuse the existing guardian override domain tests to prove that repeated grants accumulate, totals remain scoped to one rule and 사용일, future grants are ignored, and the appended difference preserves earlier ledger entries.
- Reuse the existing guardian DataStore write test style to prove that a successful authenticated write is present in the final settings value and that failed authentication or invalid input does not produce a matching grant.
- Test the initial state: current total is exposed for display, 추가 시간 is empty, and 총 추가 시간 has no entered value.
- Test delta input: current `30` plus entered `10` exposes total `40`, with no leading plus sign.
- Test total input: current `30` and entered total `45` exposes additional `15`.
- Test switching sources: an automatically calculated field can become the active source without a feedback loop or duplicated text update.
- Test equal and smaller targets: the derived field stays blank, submission returns `총 추가 시간은 현재 값보다 커야 해요.`, and no write request is produced.
- Test blank, zero, malformed, and overflow input: submission fails safely, the dialog remains conceptually open, and no write request is produced.
- Test repeated application: existing grants totaling `30` plus a valid target of `40` appends `10` and reports a new total of `40`.
- Test isolation: grants for another rule, another 사용일, a future issue time, or an older use day generation do not enter the displayed total.
- Test authentication outcomes at the existing activity boundary where practical: success writes and relaunches, while wrong password and cancellation do neither.
- Keep visual and IME behavior verification focused: confirm the initial placeholder clears on first edit, a populated field selects all on focus, both fields remain usable with the numeric keyboard, errors render beneath the correct field, and the dialog fits narrow screens with the keyboard open.
- Run the focused new JVM tests first, then the existing guardian override and guardian write tests, then `testFullDebugUnitTest`.
- Because the changed activity and resources are shared source, assemble `fullDebug`, `playstoreDebug`, and `fdroidDebug` after the focused tests pass.
- A good test observes paired values, validation, persisted grant totals, authentication outcomes, or relaunch behavior. It does not freeze the class layout, listener order, or internal coroutine structure.

## Out of Scope

- Decreasing the 지급한 보호자 추가시간 총량.
- Entering a negative 추가 시간.
- Setting 총 추가 시간 to zero or revoking an unused grant.
- Editing guardian extra time from the 앱 규칙 management screen.
- Showing used or remaining guardian extra time in this popup.
- Changing the legacy warning screen, its minute picker, or its temporary cooldown persistence.
- Adding a global guardian extra time pool shared by several rules.
- Changing the rule evaluator's allowance allocation or grant consumption semantics.
- Replacing earlier grant records or rewriting grant issue timestamps.
- Adding a product maximum for grant minutes.
- Changing the configurable 사용일 reset boundary.
- Changing guardian credential storage, retry behavior, or session policy.
- Room schema changes or destructive migration.
- Sync, API, notification, manifest, or build flavor behavior changes.
- Adding Compose, Navigation components, or an architectural redesign of the approval activity.

## Further Notes

- The displayed `현재 총 추가 시간` means 지급한 보호자 추가시간 총량, not remaining time and not total app usage.
- `오늘` is intentionally absent from the labels because Curbox uses a configurable 사용일 boundary rather than calendar midnight.
- The normal quota exhaustion path reaches this screen after the existing guardian grant is consumed. Structural rule errors may also produce a denial, but this feature must not reinterpret those errors as permission to decrease or revoke grants.
- No ADR is required. The change is local, reversible, and does not meet the repository threshold for an architectural decision record.
- Keep the implementation context below 200k tokens. Target 120k to 160k total by following the tracer bullet checkpoints. For a fresh implementation context, load only the glossary, this spec, the approval activity, guardian override operations, the owner DataStore grant transaction, the dialog resources, and the two focused guardian test suites. Do not load the full app rules design history unless a contradiction appears.
- If a checkpoint approaches 160k tokens, stop after leaving a compiling diff and a short local handoff note, then continue in a fresh context using this spec and that diff as the source of truth.
- This specification is intentionally stored as a local project file and is not published to an issue tracker.
