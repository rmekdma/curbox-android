# 01 — 선택한 규칙의 총 추가 시간을 증가시킨다

**What to build:** 앱 규칙 잠금 화면에서 선택한 규칙의 현재 사용일 총 추가 시간을 확인하고, 새로 추가할 양을 입력해 변경 후 총량을 미리 본 다음 지급할 수 있게 한다. 보호자 인증이 끝나면 기존 지급 기록을 보존한 채 입력한 증가분 하나만 새로 지급한다. 총 추가 시간을 직접 편집하는 역방향 입력은 다음 티켓에서 추가한다.

**Blocked by:** None — can start immediately.

**Status:** done

**Expected context:** 100k tokens 이하

- [x] 잠금 화면의 기존 진입 동작이 `추가 시간 변경`을 열고, 선택한 규칙의 현재 사용일에 지급한 총 추가 시간을 보여준다. 표시값은 사용 여부와 관계없이 지급한 총량이다.
- [x] 표시값에는 다른 규칙, 이전 사용일, 이전 reset generation과 미래 시각에 발급된 지급분이 섞이지 않는다.
- [x] 팝업에서 양수 `추가 시간`을 입력하면 `총 추가 시간` 미리보기가 현재 총량과 입력값의 합으로 계산된다. 양수 변경량은 `10`처럼 부호 없이 표시된다.
- [x] 빈 값, 0, 형식이 잘못된 값, 숫자 파싱 범위 초과, 현재 총량과 증가분의 덧셈 오버플로와 분 단위의 기간 변환 오버플로는 인증이나 저장 전에 거부되며 팝업이 열린 상태로 유지된다.
- [x] 유효한 값으로 `적용`하면 기존과 같은 보호자 인증을 거친 뒤 입력한 증가분 하나만 지급하고, 이전 지급 기록은 그대로 유지한다.
- [x] 인증 취소, 잘못된 인증과 저장 실패는 지급분을 추가하거나 대상 앱을 다시 열지 않는다. 성공적으로 저장된 경우에만 기존 흐름대로 대상 앱을 다시 연다.
- [x] 편집과 검증 중에는 `적용`을 활성화하되, 인증 또는 저장이 진행 중일 때 중복 제출을 막아 한 번의 제출이 최대 한 지급만 만든다.
- [x] 팝업을 다시 열면 방금 지급한 증가분이 반영된 총량을 보여주며, 한 규칙의 승인으로 다른 거부 규칙이 해제되지 않는다.
- [x] 정상 입력, 무효 입력, 반복 지급, 규칙별 범위, 현재 사용일, reset generation과 지급 시각 범위를 검증하는 집중 테스트가 통과한다.

## Evidence

- `GuardianExtraTimeFormState` is the public pure form seam. Its focused tests cover the initial state, positive delta preview, malformed and zero input, parse overflow, total overflow, and minute duration overflow.
- `GuardianApprovalActivity` reads the normalized selected rule and current use day, renders the dedicated Material dialog, validates before authentication, appends through `DataStoreManager.grantAppRuleTime`, and relaunches only after a successful write. The in flight guard blocks repeated Apply taps and new grant dialogs until authentication and storage finish.
- Existing guardian override tests cover future grants, older use days, reset generations, repeated ledger entries, independent rule pools, and denial preservation. The existing DataStore guardian write test remains green.
- Focused guardian/form tests passed after review fixes. Final `testFullDebugUnitTest` passed with 281 tests, 281 passed, 0 failed, 0 skipped, and 0 errors. `assembleFullDebug`, `assemblePlaystoreDebug`, and `assembleFdroidDebug` all passed.
- Standards review found one P3 validation duplication; the pure validation helper was consolidated, and the reviewer withdrew the pre-existing activity repetition after rebuttal. Spec review found one P1 in flight duplicate grant path; the guard was fixed and the same reviewer rechecked it as resolved. Both reviews used `gpt-5.6-luna` with max reasoning.
