# 02 — 총 추가 시간 직접 입력과 연동 계약을 완성한다

**What to build:** 첫 번째 tracer bullet에 `총 추가 시간` 직접 입력을 더한다. 사용자는 현재 총량을 계속 확인하면서 추가할 양과 목표 총량 사이를 자연스럽게 전환할 수 있고, 같거나 작은 목표나 무효한 값을 적용하려 할 때 인증과 저장 없이 명확한 안내를 받는다.

**Blocked by:** 01 — 선택한 규칙의 총 추가 시간을 증가시킨다.

**Status:** done

**Expected context:** 80k tokens 이하

- [x] 입력란 위에 `현재 총 추가 시간`이 계속 표시되고, 직접 편집 가능한 총 추가 시간 입력란은 처음에 현재 총량을 placeholder로 보여준다.
- [x] 총 추가 시간 입력란을 처음 탭하면 placeholder가 사라지며, 연동 계산으로 실제 값이 채워진 뒤 다시 탭하면 그 값 전체가 선택된다.
- [x] `추가 시간`을 바꾸면 목표 총량이 다시 계산되고, 현재 값보다 큰 `총 추가 시간`을 바꾸면 추가 시간이 `목표 총량에서 현재 총량을 뺀 값`으로 다시 계산된다. 입력 소스를 전환해도 피드백 루프나 중복 갱신이 발생하지 않는다.
- [x] 총 추가 시간을 직접 입력하는 동안 값이 비어 있거나 현재 총량보다 작거나 같거나 해석할 수 없으면 `추가 시간` 입력란을 비워 잘못된 증가량이나 음수를 표시하지 않는다.
- [x] 편집과 검증 중에는 `적용`이 활성화되어 있다. 현재 총량과 같거나 작은 값을 적용하면 인증이나 저장을 시작하지 않고, 팝업을 유지하며 총 추가 시간 Material 입력란에 `총 추가 시간은 현재 값보다 커야 해요.`를 표시한다.
- [x] 총량 파싱, `목표 총량에서 현재 총량을 빼는 계산`과 분 단위의 기간 변환에서 발생할 수 있는 모든 기술적 오버플로는 저장하거나 화면을 종료하지 않고 안전하게 거부된다.
- [x] 인증 또는 저장이 진행 중일 때 중복 제출을 막으며, 오류를 고친 뒤 다시 적용하면 정확히 한 증가분만 지급된다.
- [x] 사용자 문구와 요구사항 문서가 `추가 시간`, `총 추가 시간`, 현재 사용일의 규칙별 지급 총량이라는 확정된 의미를 일관되게 사용한다.
- [x] 양방향 입력 전환, placeholder와 전체 선택, 오류 후 수정, 인증 취소와 재시도를 행동 중심으로 검증하는 집중 테스트가 통과한다. Activity 수준 자동화는 기존 테스트 기반에서 실용적인 범위로 한정한다.
- [x] 집중 테스트와 `testFullDebugUnitTest`가 통과하고, 공용 소스 변경에 필요한 `fullDebug`, `playstoreDebug`, `fdroidDebug` 빌드가 성공한다.

## Evidence

- `GuardianExtraTimeFormState`에 추가 시간과 총 추가 시간의 활성 입력 소스, 양방향 파생 계산, 공통 검증과 overflow 거부를 구현했다. `GuardianExtraTimeFormStateTest`가 유효한 전환, equal/smaller target, blank/zero/malformed/parse overflow, duration overflow를 검증한다.
- `GuardianApprovalActivity`와 dialog layout이 현재 총량 표시, 편집 가능한 총량 placeholder, 최초 focus 처리, 계산값 전체 선택, watcher feedback loop 방지, 오류 후 복구, Material field error 표시를 제공한다. 유효한 submit만 인증과 저장으로 진행하고, 진행 중 중복 제출은 guard로 차단한다.
- 기존 guardian boundary 테스트(`AppRuleGuardianOverridesTest`, `DataStoreGuardianWriteTest`)와 focused form 테스트가 통과했다. Activity 수준 자동화는 기존 테스트 기반의 실용적 범위로 유지했다.
- 최종 `testFullDebugUnitTest` 결과 XML 집계: 287 passed, 0 failed, 0 skipped, 0 errors. `assembleFullDebug`, `assemblePlaystoreDebug`, `assembleFdroidDebug` 모두 BUILD SUCCESSFUL.
- 최종 리뷰는 Standards와 Spec 모두 actionable finding 없음으로 종료됐다. 따라서 적용하거나 반박할 finding이 없었고, 리뷰 후 소스 변경은 없다.
- 상세 spec `docs/specs/guardian-extra-time-total.md`는 읽기만 했으며 untracked 상태로 보존하고 stage하지 않았다.
