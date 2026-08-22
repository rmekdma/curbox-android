# 05 — 보호자 인증과 규칙별 임시 승인 완성

**What to build:** 보호자가 기존 제거 방지 비밀번호와 분리된 로컬 비밀번호로 Curbox 관리 화면과 제한 약화 작업을 보호하고, 잠긴 규칙 하나에 실제 전면 사용 추가시간을 주거나 다음 복원 시각 이내까지 규칙을 건너뛰게 한다. 각 승인은 규칙별로 독립적이며 다른 규칙의 차단을 우회하지 않는다.

**Blocked by:** 03 — 복합 적용 범위와 시간 구간 완성; 04 — 사용 조건과 적립 허용량 완성.

**Status:** done

**Verification evidence (JBR 21, 2026-08-18):**

- Final focused JBR21 guardian/override command passed (41 tests), covering guardian session/password, onboarding route policy, reevaluation, override calculation, warning selection, DataStore write results, sync isolation, external-return commit blocking, focus loss/gain reauthentication, successful reveal, and owned-dialog/bottom-sheet state cleanup seams.
- `testFullDebugUnitTest`: 219 tests completed; one fixed-base failure remains at `ScriptLanguageTest > matchesRegexSupportsCommonFlags`.
- `assembleFullDebug assemblePlaystoreDebug assembleFdroidDebug` passed after the final source/resource changes.
- `lintFullDebug` passed after the final source/resource changes; the report contains existing baseline findings, no changed-file `SetTextI18n` finding, and no new Guardian issue.
- Configured guardian credentials now gate exported onboarding routes before content creation; no-password onboarding remains auth-free. `GuardianOwnedBottomSheet` centralizes settings-sheet ownership and commit checks, while `GuardianOwnedDialog` covers nested dialogs and restriction-weakening callbacks with stale-session rejection.
- No Android device or emulator was available. Device verification procedure: install the Full debug APK, set a guardian password, leave Curbox for another app and return to confirm reauthentication, trigger two simultaneous denying rules and confirm every denial is shown with one selectable approval, exercise both timed skip choices and additive time, then repeat on Play Store and F-Droid APKs while checking the Play Store manifest has no `AdminReceiver` or `NodePickerService`.
- F-Droid sync route and complete locale translation coverage were not changed; they remain fixed-base or out of scope for this review fix.

- [x] 보호자 비밀번호는 기존 제거 방지 비밀번호와 분리된 기기 로컬 자격 증명이다.
- [x] 비밀번호 검증 값은 임의 솔트와 느린 비밀번호 파생 함수로 저장되며 평문이나 재사용 가능한 원본 해시는 저장하지 않는다.
- [x] 비밀번호 최소 길이와 오입력 횟수 제한은 추가하지 않는다.
- [x] 비밀번호가 설정되어 있으면 실제 다른 앱이나 task로 이동했다가 Curbox 관리 화면에 돌아올 때 재인증을 요구한다.
- [x] Curbox 소유 대화상자와 구성 변경은 인증을 유지하고, Curbox가 명시적으로 시작해 결과를 기다리는 단발성 시스템 화면은 보호 콘텐츠를 가린 상태에서 인증을 유지할 수 있다.
- [x] Android Settings 등 외부 화면이나 예상하지 못한 focus 손실 뒤에는 보수적으로 재인증한다.
- [x] 비밀번호가 없으면 관리 화면, 규칙별 추가시간과 건너뛰기를 인증 없이 사용할 수 있다.
- [x] 보호자 추가시간은 선택한 규칙 하나의 현재 사용일에 실제 전면 사용 허용량으로 더해진다.
- [x] 보호자 추가시간은 사용 조건 미달과 직접 사용 가능 시간 0인 규칙에서도 지급된 시간만큼 실제 사용을 허용한다.
- [x] 보호자는 규칙의 비활성 구간에도 추가시간을 미리 지급할 수 있고 지급분은 다음 활성 구간에서만 소비된다.
- [x] 같은 규칙에 여러 번 지급한 추가시간은 누적되고 여러 활성 구간에서 나누어 소비된다.
- [x] 추가시간은 규칙의 대상 앱이 활성 구간에 실제로 보일 때만 소비되고 다음 복원 시각에 남은 값이 사라진다.
- [x] 선택 시각까지 건너뛰기와 다음 복원 시각까지 건너뛰기를 제공하며 선택 시각은 다음 복원 시각을 넘길 수 없다.
- [x] 건너뛴 동안 전역 세션은 계속 저장되지만 해당 규칙의 소비량에는 포함되지 않는다.
- [x] 다른 적용 규칙은 추가시간이나 건너뛰기의 영향을 받지 않고 계속 판정 및 소비한다.
- [x] 잠금 화면은 현재 거부 중인 규칙 이름과 원인을 모두 표시하고 보호자가 승인할 규칙 하나를 선택하게 한다.
- [x] 승인 직후 모든 적용 규칙을 다시 평가하고 다른 거부 규칙이 남으면 잠금을 유지한다.
- [x] 겹치는 두 규칙에 각각 추가시간을 주면 한 앱의 같은 전면 분이 두 규칙의 독립 할당량을 함께 소비한다.
- [x] 모든 규칙을 한 번에 건너뛰거나 하나의 추가시간 풀을 여러 규칙이 공유하는 기능은 제공하지 않는다.
- [x] 승인 상태는 안정적인 규칙 ID와 현재 사용일에 묶어 로컬에 저장되고 규칙 비활성화나 기존 경고 쿨다운으로 표현하지 않는다.
- [x] 비밀번호 확인과 승인 상태 쓰기는 신뢰된 앱 내부 작업으로 연결되고 외부 브로드캐스트 입력만으로 승인되지 않는다.
- [x] Device Owner와 Lock Task를 추가하지 않으며 보호 문구는 접근성, 오버레이와 일반 Device Admin 기반 최선형 보호라고 설명한다.
- [x] 관리 화면 재진입, 추가시간, 두 건너뛰기 방식, 겹치는 규칙과 복원 경계가 자동화 테스트로 검증된다.
- [x] 조건 미달, 직접 허용량 0, 비활성 구간 사전 지급과 승인 후 전체 재평가가 자동화 테스트로 검증된다.
- [x] 인증 및 승인 상태의 다중 프로세스 전달과 실패 격리가 이 수직 기능에서 검증된다.
- [x] 인증 화면과 민감 화면 가리기는 계측 테스트 또는 기기 검증 절차로 확인한다.
- [x] full, playstore, fdroid 변형이 모두 컴파일되고 지원되지 않는 Device Admin 진입점이 playstore에 노출되지 않는다.
