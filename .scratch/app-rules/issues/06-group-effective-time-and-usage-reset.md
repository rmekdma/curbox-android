# 06 — 앱 그룹 소급 변경과 사용량 초기화 완성

**What to build:** 보호자가 앱 그룹을 수정할 때 새 구성을 지금부터 적용하거나 현재 사용일 시작부터 소급 적용하고, 필요하면 앱 하나 또는 앱 그룹 전체의 오늘 사용시간과 실행 횟수를 즉시 초기화한다. 변경 결과는 사용량 페이지, 모든 대상 규칙과 모든 기여 계산에 같은 의미로 반영된다.

**Blocked by:** 02 — 정확한 사용일과 다중 전면 앱 집계 완성; 03 — 복합 적용 범위와 시간 구간 완성; 04 — 사용 조건과 적립 허용량 완성; 05 — 보호자 인증과 규칙별 임시 승인 완성.

**Status:** implemented-review-passed; environment-verification-blocked

**Verification evidence (2026-08-18):**

- Standards and spec reviews both reached no-blocker verdicts after reset/session, pending-change, lifecycle, and completion-race fixes.
- Pure Kotlin compilation passed before the final Android lifecycle refinements. Pure JUnit policy/domain checks passed (14 tests); the final small UI-policy regression was added after the last runnable compiler pass.
- `git diff --check` passed for the ticket changes.
- Gradle verification could not run: the sandbox could not download Gradle 8.13, offline resolution lacked the Foojay plugin, and the available Gradle cache lock/jar was access-denied. Therefore Room integration, multiprocess instrumentation, `testFullDebugUnitTest`, and the three flavor builds remain unverified.
- No device or emulator verification was available. Exercise app/group reset during foreground split-screen use, service death during reset commit and rollback, delayed completion, and pending group edits with different effective modes before release.

- [x] 앱 그룹 저장 시 `지금부터 적용`과 `오늘부터 적용` 중 하나를 명시적으로 선택할 수 있다.
- [x] 설정 변경 지연이 적용된 그룹 변경에서 지금부터는 실제 적용 순간, 오늘부터는 실제 적용 순간이 속한 사용일 시작을 뜻한다.
- [x] 대기 상태에는 적용 모드만 보존하고 실제 기준 시각은 지연 변경을 적용하는 트랜잭션에서 결정한다.
- [x] 지금부터 적용은 변경 이전 세션의 과거 그룹 의미를 바꾸지 않고 변경 시점 이후 세션에만 새 구성원을 적용한다.
- [x] 오늘부터 적용은 현재 사용일 시작 이후 세션을 새 구성으로 다시 계산한다.
- [x] 대상 범위에 오늘부터 추가된 앱은 해당 규칙의 활성 구간과 겹친 현재 사용일 세션만 규칙 소비량에 포함된다.
- [x] 기여 그룹에 오늘부터 추가된 앱은 현재 사용일 전체 세션이 기여 사용량에 포함된다.
- [x] 그룹에서 제거된 앱도 선택한 적용 시점에 따라 대상 및 기여 계산에서 대칭적으로 빠진다.
- [x] 여러 그룹의 중복 구성원은 각 규칙의 합집합 의미에 따라 한 번만 계산된다.
- [x] 보호자는 앱 하나의 오늘 사용량 초기화를 실행할 수 있다.
- [x] 보호자는 앱 그룹 하나를 선택해 현재 구성원 앱 각각의 오늘 사용량을 전역으로 초기화할 수 있다.
- [x] 초기화는 오늘 사용시간과 오늘 실행 횟수를 0으로 만들고 마지막 사용 시각은 보존한다.
- [x] 그룹 초기화에서 다른 그룹과 겹치는 앱도 전역 앱 사용량이 초기화되므로 모든 관련 그룹과 규칙에 반영된다.
- [x] 초기화 순간 전면에 보이는 앱은 그 시점에서 기존 세션을 끝내고 즉시 새 세션과 실행 횟수 누적 규칙을 시작한다.
- [x] 초기화는 사용량 페이지, 대상 규칙 소비량, 사용 조건과 적립 허용량을 한 번의 일관된 결과로 갱신한다.
- [x] 그룹 편집은 현재 유효한 관리 세션 인증으로 충분하며 사용량 초기화는 비밀번호가 설정된 경우 새 보호자 인증을 요구한다.
- [x] 사용량 초기화는 영향 범위를 설명하는 확인 경고 후 즉시 적용되고 설정 변경 지연을 거치지 않는다.
- [x] 초기화 대상 세션에서 제거할 시간과 실행 횟수 차이를 계산하고 하나의 Room 트랜잭션에서 세션을 절단 또는 삭제한 뒤 기존 날짜 및 시간대 집계에서 정확한 차이만 차감한다.
- [x] 같은 달력 날짜에 있지만 이전 사용일에 속한 시간, 실행 횟수와 마지막 사용 시각은 초기화로 손실되지 않는다.
- [x] 세션 원장은 실행 발생 시각을 보존해 현재 사용일의 실행 횟수 차이를 정확히 계산할 수 있다.
- [x] 그룹 구성 변경은 제한 강도에 따라 기존 설정 변경 지연 판정을 계속 적용한다.
- [x] 그룹 수정 및 초기화 중 일부 저장이 실패해 통계와 규칙 상태가 서로 다른 부분 성공 상태로 남지 않는다.
- [x] 현재 사용일 세션이 정리된 이후에는 지원 범위를 벗어난 과거 소급 재계산을 약속하지 않는다.
- [x] 추가, 제거, 중복, 대상 역할, 기여 역할, 분할 화면과 활성 건너뛰기 중 변경이 자동화 테스트로 검증된다.
- [ ] 앱 및 그룹 초기화, 현재 전면 재누적과 모든 파생 계산 갱신이 자동화 테스트로 검증된다.
- [ ] 그룹 변경과 초기화의 다중 프로세스 전달, 원자성 및 실패 격리가 이 수직 기능에서 검증된다.
- [ ] full, playstore, fdroid 변형이 모두 컴파일된다.
