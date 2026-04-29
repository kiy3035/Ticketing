# 01. 테스트 전략

## 1. 테스트 피라미드

이 프로젝트는 표준 테스트 피라미드 + 동시성 테스트 + 아키텍처 테스트의 5단계 구조를 따른다.

```
                   ▲
                   │            (E2E — 수동/k6 부하테스트로 대체)
                  ╱─╲
                 ╱   ╲          ⬆ Concurrency Test (느림, 비싸지만 핵심)
                ╱─────╲           - 100 thread → 1 success
               ╱       ╲
              ╱─────────╲       ⬆ Integration Test (Testcontainers)
             ╱           ╲        - 실제 Redis/MySQL/Kafka로 contract 검증
            ╱─────────────╲
           ╱  Slice Test   ╲    ⬆ Slice Test (@WebMvcTest)
          ╱                 ╲     - HTTP 계약(상태 코드, JSON 형식)
         ╱───────────────────╲
        ╱     Unit Test       ╲ ⬆ Unit Test (Mockito) — 가장 많아야 함
       ╱                       ╲  - 비즈니스 분기, 예외 케이스
      ╱─────────────────────────╲
       Architecture Test (ArchUnit)  ← 옆에서 레이어 의존성 강제
                   │
```

## 2. 어디서 무엇을 검증하나?

| 레벨 | 도구 | 무엇을 검증 | 실행 시간 | 인프라 |
|------|------|-------------|----------|---------|
| **Unit** | JUnit 5 + Mockito | 단일 클래스의 비즈니스 분기, 예외 처리 | 수십 ms | 없음 |
| **Slice** | `@WebMvcTest` + MockMvc | 컨트롤러 HTTP 계약 (상태 코드, JSON 응답) | 1~3초 | 없음 (Mock) |
| **Integration** | `@SpringBootTest` + Testcontainers | 실제 Redis Lua 스크립트, MySQL 트랜잭션, Kafka 발행 | 30초~1분 (컨테이너 부팅) | Docker |
| **Concurrency** | ExecutorService + CountDownLatch + 통합 테스트 | 분산 락의 정확성 (race condition) | 수 초 | Docker |
| **Architecture** | ArchUnit | 패키지 의존성 규칙 (controller → repository 금지 등) | 1초 미만 | 없음 |

## 3. 왜 이렇게 나눴나? (설계 결정)

### 3-1. 단위 테스트는 Mock으로 빠르게

- **이유**: 비즈니스 로직의 분기(예: "락 실패 시 429", "좌석 없으면 404")는 **수백 개로 늘어날 수 있고**, 매번 Redis를 띄우면 CI가 느려진다.
- **결정**: `@ExtendWith(MockitoExtension.class)`로 의존성을 모두 Mock 처리해서 ms 단위 실행 보장.
- **트레이드오프**: Redis Lua 스크립트의 실제 동작은 검증하지 못함 → 통합 테스트로 보완.

### 3-2. 통합 테스트는 Testcontainers로

- **이유**: Redis 분산 락은 **Lua 스크립트의 원자성**이 핵심이라 Mock으로는 검증 불가.
- **결정**: `IntegrationTestBase`에서 MySQL/Redis/Kafka 컨테이너를 자동 기동.
- **장점**: CI 환경에서도 외부 인프라 의존 없이 동일하게 실행 가능. 로컬과 CI의 환경 차이로 인한 flaky test 방지.

### 3-3. 동시성 테스트를 별도 패키지로

- **이유**: 동시성 버그는 단위 테스트로는 절대 잡을 수 없음. 실제 멀티스레드 + 실제 Redis가 필요.
- **결정**: `concurrency/` 패키지에 격리. `CountDownLatch`로 모든 스레드가 동시에 출발하도록 보장.
- **검증 시나리오**:
  - 100 스레드가 같은 좌석에 동시에 홀드 시도 → **정확히 1개만 성공**
  - 50 스레드가 같은 락 키에 동시 시도 → **정확히 1개만 토큰 획득**

### 3-4. ArchUnit으로 레이어 의존성을 CI에서 강제

- **이유**: 코드 리뷰만으로는 "controller가 repository를 직접 import 한 실수"를 놓치기 쉬움.
- **결정**: `ArchitectureTest`에서 3가지 규칙을 ArchUnit으로 자동 검증.
  1. Controller → Repository 직접 의존 금지
  2. Domain → Spring 프레임워크 의존 금지
  3. Service → Controller 의존 금지 (역방향 의존 차단)

## 4. 테스트하지 않는 것 (의도적 제외)

| 대상 | 이유 |
|------|------|
| Lombok 자동 생성 getter/setter | 테스트할 가치 없음 |
| Spring 프레임워크 자체 | 신뢰함 |
| 외부 SaaS (메일, SMS) | 테스트 환경에서는 더미 값으로 무효화 (`IntegrationTestBase` 참조) |
| UI/프론트엔드 | 백엔드 포트폴리오 범위 밖 |

## 5. 면접 답변 시 포인트

- **"테스트 비용 vs 신뢰성"** 트레이드오프를 의식하고 피라미드를 설계함
- **Testcontainers** 도입으로 "Mock 한계" + "환경 의존" 두 문제를 동시에 해결
- **동시성 테스트**는 단순히 "테스트 작성"이 아니라 **"분산 락의 정확성을 코드로 증명"** 하는 핵심 산출물
- **ArchUnit**으로 인적 실수를 자동화로 막음 → "리뷰만으로는 한계"라는 실무 감각
