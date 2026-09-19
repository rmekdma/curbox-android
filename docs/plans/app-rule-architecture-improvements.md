# 앱 규칙 enforcement 아키텍처 검토 및 개선 방안

## 1. 개요 및 현재 상태 평가

본 문서는 `app-refactor` 브랜치에서 진행된 앱 규칙(App Rules) enforcement 리팩토링의 결과를 `/codebase-design` 원칙(모듈, 인터페이스, 깊이, 이음새, 어댑터, 레버리지, 지역성)에 따라 분석하고, 관찰된 미흡한 점과 구체적인 구조 개선 방안을 정리한 문서입니다.

### 1.1 리팩토링의 주요 성과 (잘 된 점)

* **`ForegroundEvidenceModule`의 높은 깊이(Depth)**
  * 복잡한 13가지 전면 증거 정책(R1부터 R13까지: root null, 창 stale, split screen unknown 슬롯, R5 A fail closed 판정 등)을 단 하나의 순수 인터페이스인 `classify(ForegroundFacts, ForegroundEvidencePolicySnapshot): ForegroundEvidenceResult` 뒤로 완전히 은닉했습니다.
  * 안드로이드 프레임워크나 서비스 의존성 없이 30여 개의 결정론적 단위 테스트로 정책 전체를 즉시 검증할 수 있습니다.
* **`ForegroundObservationSource` 이음새(Seam)와 두 개의 실제 어댑터(Adapters)**
  * `AndroidForegroundObservationSource`(실제 접근성 서비스 기반 관찰)와 `DeterministicForegroundObservationSource`(테스트용 가상 관찰)라는 두 개의 실제 구현 어댑터를 제공합니다.
  * 가설적 이음새가 아닌 실제 동작하는 이음새 원칙(Two Adapters Rule)을 충족합니다.
* **`SerializedDecisionWorker`의 메인 스레드 격리**
  * 접근성 서비스 콜백 루퍼에서 무거운 작업인 Room 세션 저장(1시간 단위 체크포인트 분할), 규칙 평가(`AppRuleEvaluator`), 동시성 제어 및 취소 격리를 작업자 내부로 격리했습니다.
  * 메인 스레드 지연 및 예외 전파 위험을 제거했습니다.
* **핵심 불변식 보존**
  * 사용자 데이터 파괴를 유발하는 Room 데이터베이스 스키마 변경을 방지했습니다.
  * 세 가지 빌드 flavor(`full`, `playstore`, `fdroid`)와 접근성 서비스 비정상 종료 방지 불변식을 안전하게 보존했습니다.

---

## 2. 미흡한 점 및 구조적 마찰 (Shortcomings)

### 2.1 `AppRuleBlocker`의 비대화와 책임 과중 (4331줄)
* **현상**: 핵심 도메인 로직을 분리했음에도 불구하고 `AppRuleBlocker.kt`는 4331줄에 달합니다.
* **원인**: 알람 매니저 등록, 토큰 매칭, 복구 콜백, 재확인 스케줄링, 드레인 작업 추적 등 1000줄 이상의 플랫폼 스케줄링 및 수명 주기 부가 로직이 블로커에 직접 누적되어 있습니다.
* **마찰**: 단일 클래스의 인지 부하가 매우 높고, 코드 수정 시 의도치 않은 사이드 이펙트 발생 위험이 큽니다.

### 2.2 `AppRuleWallClockScheduler`의 얕은 모듈(Shallow Module) 한계
* **현상**: `AppRuleWallClockScheduler.kt`는 22줄짜리 단순 시간 산술 헬퍼(`delayUntil`)에 불과합니다.
* **원인**: 모듈이 순수 계산식만 담고 있어, 플랫폼 `AlarmManager` 연동, `PendingIntent` 발급 및 취소, `Handler` 폴백, 스케줄러 토큰 매칭, wake 복구 등 실질적인 스케줄링 메커니즘의 95% 이상을 호출자인 `AppRuleBlocker`가 직접 떠안고 있습니다.
* **마찰**: 인터페이스 대비 레버리지가 극히 낮아, 호출자 쪽에 수많은 상태 맵(`scheduledRechecks`, `scheduledAlarms`, `scheduledRecoveryCallbacks`)과 헬퍼 함수가 증식했습니다.

### 2.3 접근성 창 출처 캐시(`ApplicationWindowProvenanceCache`)의 누출
* **현상**: `ApplicationWindowProvenanceCache` 클래스가 `AppRuleBlocker.kt` 하단에 중첩 클래스로 정의되어 있습니다.
* **원인**: 관찰 토큰과 이전 창 스냅샷을 조합하여 현재 창의 유효성을 판단하는 로직이 블로커 클래스 내부에 잔존합니다.
* **마찰**: 관찰 어댑터(`AndroidForegroundObservationSource`) 내부로 완전히 숨겨져야 할 세부 캐싱 전략이 상위 블로커로 누출되어 이음새의 순수성을 저해합니다.

### 2.4 블로커와 결정 작업자 간의 양방향 핑퐁 및 파편화된 콜백
* **현상**: `SerializedDecisionWorker` 생성 시 `outcomeSink` 외에도 `onRecheckPlan`, `onEvaluation`, `onUsageResetComplete`, `onRequestCancellation` 등 여러 람다 콜백을 주입합니다.
* **원인**: 작업자가 평가를 마친 뒤 재확인 계획을 블로커에 역호출하고, 블로커가 이를 받아 알람을 예약한 뒤, 알람 발생 시 다시 작업자에 요청을 제출하는 순환 구조가 형성되어 있습니다.
* **마찰**: 작업자의 실행 결과가 여러 경로로 파편화되어 전달되므로, 호출자가 관리해야 하는 콜백 배선이 복잡해집니다.

### 2.5 티켓 19 디버그 레지스트리의 리플렉션 사용
* **현상**: 디버그 소스셋의 `Ticket19ObserverRegistry.kt`(1989줄)에서 리플렉션(`java.lang.reflect.Field`)을 사용하여 `SerializedDecisionWorker`의 private 필드(`outcomeSink`, `onEvaluation`)를 강제로 교체하고 있습니다.
* **원인**: 티켓 19 라이프사이클 추적을 위해 작업자 내부를 관찰하려 했던 임시 프로브 코드입니다.
* **마찰**: 티켓 19는 라이프사이클 호스트가 불필요하다는 NO GO 판정으로 종결되었습니다. 그럼에도 2000줄에 달하는 리플렉션 코드가 남아 있어 작업자 내부 구현 변경 시 런타임 크래시를 유발할 수 있습니다.

### 2.6 미사용 데드라인 드레인 계측 코드 잔존
* **현상**: 티켓 28에서 사용자의 명시적 결정에 따라 운영 환경에서는 `RecoveryOnlyStop`을 유지하고 수치적 데드라인 드레인은 도입하지 않기로 확정되었습니다.
* **원인**: 그럼에도 `AppRuleBlocker` 내부에는 여전히 `DestroyDrainMeasurement`, `inFlightRefreshes`, `inFlightNotifications`, `inFlightCallbacks`, `inFlightUsageResetCompletions`, `inFlightRecheckPlans`, `pendingExternalEffects` 등 방대한 드레인 계측 코드가 남아 있습니다.
* **마찰**: 실제 사용되지 않는 복잡한 계측 코드가 상주하여 가독성을 저해합니다.

---

## 3. 구체적인 구조 개선 방안 (Actionable Improvements)

### 3.1 [개선안 1] 앱 규칙 웨이크 스케줄러 심화 모듈 구축 (최우선 추천)
* **목표**: `AppRuleBlocker`에서 1000줄 이상의 스케줄링 메커니즘을 제거하고 하나의 깊은 모듈로 은닉.
* **구조 변경**:
  * `AppRuleWakeScheduler`라는 깊은 모듈을 정의합니다.
  * 외부 인터페이스는 단순화합니다:
    * `schedule(targetWallClockMs: Long, token: Long)`
    * `cancel(token: Long)`
    * `cancelAll()`
  * 모듈 내부로 완전히 은닉할 세부 사항:
    * `AppRuleWallClockScheduler.delayUntil`을 통한 모노토닉 지연 시간 계산
    * `AlarmManager.setExactAndAllowWhileIdle` 및 `PendingIntent` 발급과 취소
    * `Handler` 메인 루퍼 폴백 및 20초 최대 주기 캡핑
    * 토큰 매칭 및 중복 예약 방지
* **기대 효과**:
  * **지역성(Locality)**: 알람 및 스케줄링 관련 버그가 스케줄러 모듈 하나에 집중됩니다.
  * **레버리지(Leverage)**: `AppRuleBlocker`는 단순 예약 명령만 호출하고 수많은 내부 맵과 콜백 상태를 제거할 수 있습니다 (블로커 크기 약 1000줄 축소).
  * **테스트 용이성**: 가상 클록을 사용하는 `FakeWakeScheduler` 어댑터를 두어 실제 슬립이나 `AlarmManager` 없이도 스케줄링 검증이 가능해집니다.

### 3.2 [개선안 2] 창 출처 캐시를 관찰 어댑터 내부로 은닉
* **목표**: `AppRuleBlocker.kt`에서 `ApplicationWindowProvenanceCache`를 제거하고 어댑터 내부 세부 사항으로 격리.
* **구조 변경**:
  * `ApplicationWindowProvenanceCache`를 `AndroidForegroundObservationSource` 내부 구현으로 이동합니다.
  * 블로커는 `provenanceToken`이나 캐시 무효화 수명 주기를 직접 다루지 않고, `ForegroundObservationSource.capture()` 호출 시 최적화된 결과만 전달받습니다.
* **기대 효과**:
  * 블로커는 플랫폼 창 계층 스냅샷 변환을 전혀 알 필요가 없어집니다.
  * 관찰 어댑터가 자신의 캐싱 수명 주기를 스스로 완결적으로 소유합니다.

### 3.3 [개선안 3] 작업자 콜백 스트림을 `DecisionOutcomeSink` 단일 이음새로 통합
* **목표**: `SerializedDecisionWorker`와 블로커 사이의 다중 람다 콜백을 단일 이음새로 단일화.
* **구조 변경**:
  * `DecisionOutcome` 실드 클래스를 확장하여 작업자의 모든 산출물을 수용합니다:
    * `DecisionOutcome.EnforcementOutcome`: 최종 allow 및 block 판정
    * `DecisionOutcome.RecheckPlanReady`: 계산된 다음 재확인 스케줄
    * `DecisionOutcome.UsageResetFinished`: 사용량 초기화 완료 신호
  * `SerializedDecisionWorker` 생성 시 개별 람다 대신 단 하나의 `DecisionOutcomeSink`만 주입받습니다.
* **기대 효과**:
  * **단일 이음새**: 모든 작업자 출력이 하나의 진입점으로 모여 순서 보장과 로깅이 단순해집니다.
  * **테스트 레버리지**: 테스트 코드에서 `RecordingOutcomeSink` 하나만으로 작업자의 모든 비동기 산출물을 완벽히 수집 및 검증할 수 있습니다.

### 3.4 [개선안 4] 티켓 19 디버그 레지스트리의 리플렉션 프로브 정리
* **목표**: 비공개 필드 해킹을 제거하여 모듈 이음새 규율 회복.
* **구조 변경**:
  * 티켓 19의 NO GO 결론에 따라, 사용하지 않는 리플렉션 래핑 로직을 제거합니다.
  * 향후 계측이 필요한 경우 리플렉션이 아닌 `DecisionOutcomeSink`를 감싸는 데코레이터 패턴(Decorator)을 공식 지원 인터페이스로 활용합니다.
* **기대 효과**:
  * 디버그 소스셋의 2000줄 취약 코드가 정리되어 코드베이스 항해성(AI navigability)이 대폭 향상됩니다.
  * 모듈 내부 변경 시 리플렉션으로 인한 숨겨진 빌드/런타임 오류 가능성이 차단됩니다.

### 3.5 [개선안 5] 미사용 데드라인 드레인 계측 코드 정리
* **목표**: 티켓 28 결정(`RecoveryOnlyStop` 유지)에 맞춰 불필요한 계측 오버헤드 제거.
* **구조 변경**:
  * `inFlightRefreshes`, `inFlightNotifications`, `inFlightCallbacks`, `pendingExternalEffects` 등 미사용 드레인 계측 필드와 스냅샷 루틴을 단순화합니다.
* **기대 효과**:
  * 블로커 내부의 락 경합 및 상태 추적 복잡도를 낮추어 코드 가독성을 극대화합니다.

---

## 4. 최종 승인된 7단계 작업 티켓 로드맵 (Implementation Sequence)

각 티켓은 작업 컨텍스트가 70k 토큰 이내로 안전하게 유지되도록 분할되었으며, 상호 의존성에 따라 아래 순서로 실행됩니다:

### 트랙 1: 독립 사전 작업 (완전 병렬 가능)
1. **[티켓 01] 창 출처 캐시를 관찰 어댑터 내부로 은닉** (예상 컨텍스트: ~45k)
   * `ApplicationWindowProvenanceCache`를 `neth.iecal.curbox.domain.apprules`의 `internal` 클래스로 이전.
   * `ForegroundObservationSource.capture`에서 트리거(`RECONNECT`, `SCREEN_OFF`, `REFRESH`)에 따른 자율 캐시 무효화.
   * `AppRuleBlocker`에서 캐시 필드 제거 및 `AppRuleBlockerDestroyFaultRedTest` 리플렉션 단언문 갱신.
2. **[티켓 02] 티켓 19 디버그 레지스트리의 리플렉션 프로브 제거** (예상 컨텍스트: ~35k)
   * `Ticket19ObserverRegistry`에서 작업자 비공개 필드 해킹 루틴(`installCausalWorkerObservers`)을 단순 제거하여 디버그 빌드 런타임 크래시 사전 차단.
3. **[티켓 03] AppRuleWakeScheduler 심화 모듈 및 가상 테스트 어댑터 구현** (예상 컨텍스트: ~40k)
   * `schedule(key, dueAtWallClockMs, token)`, `cancel(key)`, `cancelAll()` 인터페이스 및 `onWake` 콜백 제공.
   * 알람 발급/취소, 지연 변환, 20초 최대 주기 핸들러 폴백 캡슐화 및 `FakeWakeScheduler` 구현.

### 트랙 2: 스케줄러 점진적 전환 (Expand & Contract)
4. **[티켓 04] AppRuleBlocker에 AppRuleWakeScheduler 확장 연동 (Expand)** (예상 컨텍스트: ~55k)
   * `AppRuleWakeScheduler` 주입 및 알람 예약/취소 위임.
   * 레거시 내부 맵(`scheduledRechecks`, `scheduledAlarms`) 미러링을 유지하여 기존 36개 리플렉션 테스트 100% 그린 유지.
5. **[티켓 05] AppRuleBlocker 스케줄링 레거시 맵 수축 및 테스트 마이그레이션 (Contract)** (예상 컨텍스트: ~65k)
   * `AppRuleBlockerRecheckTest`의 리플렉션 검사를 `FakeWakeScheduler` 상태 단언문으로 마이그레이션.
   * 블로커 내부의 미러링 맵, 1000줄 규모의 레거시 스케줄링 코드 및 `inFlightCallbacks` 퍼밋 코드 완전 삭제.

### 트랙 3: 후속 단일화 및 위생 정리
6. **[티켓 06] 결정 작업자 콜백 스트림을 DecisionOutcomeSink 단일 이음새로 통합** (예상 컨텍스트: ~60k)
   * `DecisionOutcome`에 `RecheckPlanReady`, `UsageResetFinished` 추가.
   * `SerializedDecisionWorker` 생성자 콜백 제거 및 `DecisionOutcomeSink.publish` 단일 수신부로 통합.
7. **[티켓 07] 미사용 잔여 데드라인 드레인 계측 코드 및 카운터 정리** (예상 컨텍스트: ~50k)
   * 스케줄링 외 잔여 드레인 카운터(`inFlightRefreshes` 등) 및 `DestroyDrainMeasurement` 루틴 단순화.
   * `AppRuleBlockerDestroyFaultRedTest`의 잔여 드레인 단언문 갱신.

---

## 5. 불변식 준수 체크리스트

모든 리팩토링 단계에서 다음 규칙을 반드시 유지해야 합니다:
* [ ] Room 데이터베이스 엔티티 및 스키마 버전을 변경하지 않을 것 (사용자 데이터 보존).
* [ ] `full`, `playstore`, `fdroid` 3개 빌드 variant의 컴파일 및 동작이 모두 정상일 것.
* [ ] `AppBlockerService`의 비정상 종료를 유발하지 않도록 coroutine 예외 격리를 유지할 것.
* [ ] 접근성 이벤트 콜백(`onAccessibilityEvent`)에서 무거운 노드 순회를 수행하지 않을 것.
* [ ] 사용자 대면 텍스트 규칙을 준수할 것.
