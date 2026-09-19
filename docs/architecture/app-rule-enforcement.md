# 앱 규칙 집행 아키텍처 및 전면 증거 정책 (Enforcement Architecture & Evidence Policy)

> **문서 목적 및 위치**  
> 본 문서는 Curbox의 앱 규칙 집행(Enforcement) 서브시스템의 아키텍처 구조, 4대 모듈과 이음새(Seam), 전면 증거 분류 정책표(R1~R13) 및 핵심 엔지니어링 불변식을 정의하는 기술 설계서입니다.

---

## 1. 집행 시스템 아키텍처 개요 (Architecture Overview)

접근성 서비스 콜백의 메인 루퍼 지연을 방지하고, OEM 기기(Android 15/16 등)의 불안정한 창/루트 상태에서도 일관된 규칙 집행을 보장하기 위해 enforcement 계층은 다음과 같이 4개의 핵심 모듈/이음새로 분리되어 있습니다:

```
┌─────────────────────────────────────────────────────────────┐
│                   AppBlockerService (Host)                  │
└──────────────────────────────┬──────────────────────────────┘
                               │ AccessibilityEvent / Ticker / Reconnect
                               ▼
            ┌──────────────────────────────────────┐
            │      ForegroundObservationSource     │  ◀── [이음새: Android / Fake]
            │   (프레임워크 상태 캡처 및 Value 변환)   │
            └──────────────────┬───────────────────┘
                               │ raw ForegroundFacts
                               ▼
            ┌──────────────────────────────────────┐
            │        ForegroundEvidenceModule      │  ◀── [순수 도메인 모듈]
            │    (R1~R13 전면 증거 분류기: 깊은 모듈)  │
            └──────────────────┬───────────────────┘
                               │ ForegroundEvidenceResult
                               ▼
            ┌──────────────────────────────────────┐
            │       SerializedDecisionWorker       │  ◀── [메인 스레드 격리 비동기 작업자]
            │ 1. 세션 원장 동기화 (Room write)     │
            │ 2. 앱 규칙 평가 (AppRuleEvaluator)   │
            │ 3. 단일 이음새 발행 (OutcomeSink)    │
            └──────────────────┬───────────────────┘
                               │ 다음 재검사 예약 (Due Wall Clock)
                               ▼
            ┌──────────────────────────────────────┐
            │         AppRuleWakeScheduler         │  ◀── [시간 스케줄링 깊은 모듈]
            │ (AlarmManager + 20s 폴백 루퍼 캡슐화) │
            └──────────────────────────────────────┘
```

### 1.1 핵심 모듈 역할 및 인터페이스

1. **`ForegroundObservationSource` (이음새 Seam)**
   - **역할**: 플랫폼 접근성 API(`windows`, `rootInActiveWindow`)와 디스플레이 상태를 읽어 불변 값 객체인 `ForegroundFacts`로 변환합니다.
   - **어댑터**: 실제 서비스를 사용하는 `AndroidForegroundObservationSource`와 테스트용 `DeterministicForegroundObservationSource` 두 개의 실제 어댑터를 제공합니다.

2. **`ForegroundEvidenceModule` (깊은 모듈 Deep Module)**
   - **역할**: 안드로이드 프레임워크나 비동기 의존성 없는 순수 함수형 모듈입니다.
   - **단일 인터페이스**:
     ```kotlin
     fun classify(
         facts: ForegroundFacts,
         policy: ForegroundEvidencePolicySnapshot
     ): ForegroundEvidenceResult
     ```
   - **책임**: 13가지 전면 증거 정책(R1~R13)을 내부로 은닉하고, 각 패키지를 `Visible`, `NotVisible`, `Unknown`으로 분류합니다.

3. **`SerializedDecisionWorker` (직렬화 작업자)**
   - **역할**: 메인 스레드 블로킹을 방지하기 위해 단일 코루틴 큐에서 순차적으로 무거운 작업을 수행합니다.
   - **처리 순서**:
     1. 현재 사용일 전면 세션 원장 영속화 (Room commit)
     2. 세션 원장을 기반으로 앱 규칙 평가 (`AppRuleEvaluator`)
     3. 결과 단일 발행 (`DecisionOutcomeSink.publish`)
   - **순서 보장 (`SourceOrderReservation`)**: Settings 변경과 브로드캐스트 리프레시의 경쟁 상태(Race condition)를 방지하기 위해 `SourceOrderIdentity`와 `RuntimeRevision`을 원자적으로 예약하여 항상 최신 스냅샷만 반영합니다.

4. **`AppRuleWakeScheduler` (웨이크 스케줄러)**
   - **역할**: 플랫폼 `AlarmManager`, `PendingIntent`, `Handler` 루퍼 폴백, 모노토닉 지연 변환, 토큰 무효화 메커니즘을 캡슐화한 깊은 모듈입니다.
   - **단일 인터페이스**: `schedule(key, targetWallClockMs, token)`, `cancel(key)`, `cancelAll()`

---

## 2. 전면 증거 정책표 (Foreground Evidence Policy Matrix: R1~R13)

> **승인 근거**: 2026-08-31 제품 오너 승인 완료.  
> `VISIBLE`은 전면 사용 후보로 평가 가능함을 의미하며, `NOT_VISIBLE`은 전면이 아님을 증명함을 뜻하고, `UNKNOWN`은 어느 쪽도 입증되지 않아 보류 또는 정책에 따른 제한을 가함을 의미합니다.

| ID | 상태 조합 | 승인된 Evidence 분류 | 최대 허용 지연 | Fail Policy | 평가할 Package | 대기 / 재시도 (Wait/Retry) | Block / Allow 결정 | Evidence TTL 갱신 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **R1** | 최근 실제 이벤트 + 대상 active root 일치 | `VISIBLE` | 즉시 ($\le 1$초) | 직접 증거 적용 | 대상 및 식별된 모든 비필수 앱 | 없음 | 평가기 결과에 따름 | **예** (직접 증거) |
| **R2** | 최근 이벤트 + active root null + 대상 application window 존재 | `VISIBLE` | $\le 1$초 | null root가 대상 창 증거를 무효화하지 않음 | 대상 및 식별된 모든 비필수 앱 | 없음 (임시 예외 시 bounded retry) | 평가기 결과에 따름 | **예** (창에서 확인됨) |
| **R3** | 최근 이벤트 + 대상 창 없음 + 다른 비필수 root 없음 (창 stale/null 포함) | `VISIBLE` (단, 이벤트 경과 5초 이내만) | $\le 1.5$초 (5초 경과 시 R5 전환) | 최근 이벤트를 bounded fallback으로 사용 | 마지막 실제 이벤트 패키지 하나 | 최대 3회 bounded retry 후 이벤트 fallback | fallback 기간 동안 평가기 결과 적용 | **아니오** (합성 fallback) |
| **R4** | 만료된 이벤트 + 대상 application window 존재 | `VISIBLE` | 즉시 ($\le 1$초) | 직접 대상 창이 만료된 이벤트보다 우선 | 대상 및 식별된 모든 비필수 앱 | 없음 | 평가기 결과에 따름 | **예** (직접 창 증거) |
| **R5** | **만료된 이벤트 + 빈 창 목록 또는 null root** (창/root 예외 포함) | **`UNKNOWN`** | $\le 1.5$초 내 판정 | **[A안 확정] 마지막 비필수 패키지에 대해 Fail-closed 적용** | 마지막으로 알려진 비필수 패키지 하나 | 최대 3회 retry (250, 500, 750ms) 후 제한 판정 | 평가기가 명시적 Allow할 때만 Allow, 미달/거부/불능 시 **Block** | **아니오** |
| **R6** | 이전 후보와 다른 비필수 active root가 확실히 존재 | 이전 앱: `NOT_VISIBLE`<br>새 root: `VISIBLE` | $\le 1$초 | 이전 후보 모순 시 이전 앱은 종료, 새 root를 우선 채택 | 새 root 및 직접 식별된 패키지만 | 없음 | 이전 세션 닫고 새 패키지 평가 결과 적용 | 이전 앱: 아니오<br>새 앱: **예** |
| **R7** | 필수 UI (런처/SystemUI/IME/보호자화면) root + 대상 application window 존재 | 대상은 `VISIBLE` | $\le 1$초 | 필수 root는 앱 창의 직접 패키지 증거를 지우지 않음 | 대상 창 및 식별된 비필수 앱 | 없음 | 평가기 결과 적용 (보호자 화면 중복 실행 방지) | 대상 직접 창이 있으면 **예** |
| **R8** | 필수 UI root + 대상 application window 없음 | `UNKNOWN` | 신뢰 증거 또는 `USER_PRESENT`까지 보류 | stale 이전 패키지 재평가 금지, 기존 집행 상태 유지 | 없음 | 비필수 root 또는 `USER_PRESENT` 대기 | 새 결정 없음 (기존 보호자 화면 유지) | 아니오 |
| **R9** | 부분 식별 분할 화면 (일부 창 식별, 일부 root 누락/예외) | 식별 앱: `VISIBLE`<br>누락 슬롯: `UNKNOWN` | 식별: $\le 1$초<br>누락: $\le 1.5$초 | 식별 앱 유지, 누락 슬롯을 임의 추론하지 않음 | 식별된 각 비필수 패키지 | 누락 슬롯만 최대 3회 재시도 | 식별 앱은 평가기 결과 적용, 누락 슬롯은 보류 | 식별된 앱만 **예** |
| **R10**| 서비스 재연결 / 콜드 스타트 + 알려진 application window | `VISIBLE` | $\le 1$초 | 알려진 창은 이벤트/root 부재만으로 폐기하지 않음 | 식별된 모든 비필수 앱 | 없음 | 평가기 결과에 따름 | **예** (창 증거) |
| **R11**| 서비스 재연결 / 콜드 스타트 + 빈 창 + active root null | `UNKNOWN` | 증거 발생 후 $\le 1$초 | 임의 패키지를 생성하지 않고 다음 이벤트 대기 | 없음 | 다음 실제 증거 대기 | 새 결정 없음 | 아니오 |
| **R12**| 화면 꺼짐 (`SCREEN_OFF`) | `NOT_VISIBLE` | 즉시 | 화면 꺼진 동안 전면 사용 기록 중단 | 없음 | 대기 없음 | 앱 세션 정상 종료, 평가/잠금 실행 안 함 | 아니오 |
| **R13**| 잠금 화면 (`KEYGUARD`) 표시 중 | `UNKNOWN` | `USER_PRESENT` 후 $\le 300$ms 내 재확인 | 잠금화면 아래에서 보호자 화면 중복 실행 방지 | 없음 | `USER_PRESENT` 대기 | 새 결정 없음 (기존 잠금 유지) | 아니오 |

### 2.1 R5의 Fail-Closed A안 상세
- 장시간 보호자 추가시간 경과 후 이벤트가 발생하지 않고 플랫폼 창 상태가 불확실할 때, 무기한 제한 누락을 방지하기 위해 **A안(Fail-Closed)**이 확정되었습니다.
- 최대 3회(250ms, 500ms, 750ms) bounded retry 후에도 신뢰할 수 있는 창 증거를 얻지 못하면, 마지막으로 알려진 비필수 패키지에 대해 `EVALUATE_FAIL_CLOSED` 판정을 내립니다.
- 이 판정은 추가적인 사용량 세션을 생성하거나 TTL을 연장하지 않고, 규칙 거부 시 잠금 화면을 안전하게 띄웁니다.

---

## 3. 핵심 엔지니어링 불변식 및 플랫폼 제약 (Engineering Invariants)

### 3.1 접근성 서비스 안전성 (Crash Containment)
- `AppBlockerService`는 개별 기능 실패나 접근성 창 순회 에러로 인해 절대 비정상 종료(Crash)되어서는 안 됩니다.
- 코루틴 내부에서는 `CancellationException`만 정상적으로 재전파(rethrow)하고, 그 외 non-fatal 예외는 `CrashLogger.logNonFatalError`로 기록 후 삼킵니다.
- **`onAccessibilityEvent` 내 노드 순회 금지**: 가시성 판단과 세션 처리는 가벼운 상태 캡처 후 백그라운드 워커(`SerializedDecisionWorker`)로 오프로드합니다.

### 3.2 프로세스 분리 및 Room 불변식
- 앱은 3개 프로세스(`UI Main`, `:app_blocker_service`, `:crash_handler`)로 동작합니다.
- Room DB는 멀티 인스턴스 무효화를 지원하는 `AppDatabase.getInstance()`를 통해서만 접근합니다.
- 설정은 `DataStoreManager` 싱글톤을 통해서만 접근합니다.
- **Room 스키마 무변경**: Room의 파괴적 마이그레이션(`fallbackToDestructiveMigration`) 특성상 버전 상향은 사용자 데이터를 유실시키므로, 스키마 버전을 임의로 올리지 않습니다.

### 3.3 Deep Sleep 및 Doze 대처 (AR 005)
- 안드로이드 기기가 Doze나 Deep Sleep에 들어가면 `Handler.postDelayed()` 같은 Uptime 기반 타이머는 수면 중 만료되지 않습니다.
- 이를 보완하기 위해:
  1. `AlarmManager.setExactAndAllowWhileIdle`을 통한 스케줄링.
  2. 20초 최대 주기 캡핑 루퍼 폴백.
  3. `SCREEN_ON` 및 `USER_PRESENT` 브로드캐스트 수신 시 깨어난 즉시 가시성 및 규칙 재확인(Reconciliation) 수행.

### 3.4 접근성 기반 보호의 한계 (AR 006 / ADR 0003)
- Curbox는 개인 기기의 완전 벽돌화/복구 불가 위험을 피하기 위해 **Device Owner나 키오스크 모드를 사용하지 않습니다**.
- 접근성 서비스, 오버레이 창, 일반 기기 관리자(Device Admin)를 조합한 **최선형 보호(Best-effort)**를 제공합니다.
- 안전 모드(Safe Mode) 부팅, 앱 강제 종료(Force Stop), 시스템 설정 내에서의 직접 조작을 완전 차단하는 것은 제품 설계 범위 밖(Out of Scope)으로 수용되었습니다.

### 3.5 서비스 종료 및 드레인 정책 (AR 008 / Ticket 28)
- 서비스 종료(`onDestroy`) 시점에 무리한 수치적 타임아웃 드레인을 도입하지 않고 **`RecoveryOnlyStop`** 정책을 유지합니다.
- 종료 시에는 플래그를 세워 후속 외부 효과(알림, 경고화면 등)를 차단하고, 아직 완료되지 않은 영속 세션은 서비스 재연결 시 `AppUsageTracker.recoverOpenSessions()`를 통해 안전하게 복구합니다.
