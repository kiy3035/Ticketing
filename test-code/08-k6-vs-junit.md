# 08. k6 부하 테스트 vs JUnit 테스트 — 무엇이 다른가

> 면접에서 자주 헷갈리는 질문. 둘 다 "테스트"라는 단어를 쓰지만 **목적·검증 대상·결과 해석이 완전히 다르다.**

## 1. 한 줄 요약

| 도구 | 답하려는 질문 | 답하는 방식 |
|------|---------------|-------------|
| **JUnit / Spring Test** | "코드가 **올바르게** 동작하는가?" (correctness) | 입력 → 기대 출력. 기대와 다르면 FAIL |
| **k6** | "시스템이 **얼마나** 견디는가?" (performance) | 부하 점진 증가 → 응답시간/에러율 지표 측정 |

JUnit은 **합격/불합격**을 판단하고, k6는 **숫자(p95 latency, RPS, 에러율)** 를 만들어낸다.

---

## 2. 비교표

| 항목 | JUnit / Spring Test | k6 |
|------|---------------------|-----|
| 목적 | 기능적 정확성 검증 | 성능·확장성·한계점 측정 |
| 실행 환경 | 개발자 PC, CI | 별도 부하 서버 (k6 EC2) |
| 대상 | 단일 클래스, 단일 API | 전체 시스템 (nginx + 앱 2대 + DB + Redis) |
| 부하 수준 | 100 스레드 (인메모리) | 수천 VU (실제 HTTP) |
| 검증 방식 | `assertThat()` 통과 여부 | 통계 지표 (avg/p95/p99 latency, RPS, error rate) |
| 핵심 산출물 | "23 tests passed" | **knee point**, 처리량 한계, 병목 |
| 실행 빈도 | 매 커밋, 매 PR | 릴리스 전, 인프라 변경 후 |
| 결과 해석 | 이진 (Pass/Fail) | 그래프·수치 분석 필요 |

---

## 3. 같은 좌석 홀드 시나리오를 두 도구로 검증하면?

### JUnit (`SeatHoldConcurrencyTest`)
```java
// "100명이 동시 시도 → 정확히 1명만 성공" — correctness 증명
int threadCount = 100;
// ... CountDownLatch로 동시 출발
assertThat(successCount.get()).isEqualTo(1);   // ← 1명 아니면 FAIL
```
**답하는 질문**: "분산 락이 race condition 없이 동작하는가?"
**결과**: PASS/FAIL

### k6 (`load-tests/`)
```javascript
// 5분간 1000명이 좌석 홀드 시도 → p95 latency, RPS 측정
export const options = {
    stages: [{ duration: '5m', target: 1000 }]
};
export default function() {
    http.post('/api/holds', { ... });
}
```
**답하는 질문**: "1000명이 몰릴 때 응답시간이 얼마나 늘어나는가? 어디서 무너지는가?"
**결과**: 평균 120ms, p95 450ms, 1200 RPS, 에러율 0.3%

**둘 다 필요한 이유**:
- JUnit이 통과해도 1만 명 몰리면 무너질 수 있다 (k6의 영역)
- k6가 처리량을 보여줘도 "1명만 성공" 같은 비즈니스 정확성은 못 본다 (JUnit의 영역)

---

## 4. 면접 답변 스크립트

### Q. "테스트 코드 작성해 보셨어요?"
> "네, JUnit 기반 단위·통합·동시성 테스트 23개를 작성해 100% 통과합니다.
> 추가로 **k6로 부하 테스트도 별도 진행해 knee point와 RPS를 측정**했습니다.
> JUnit은 정확성, k6는 성능 — **두 도구의 책임이 다르다**고 보고 분리했습니다."

### Q. "동시성 어떻게 검증했어요?"
> "두 가지 레벨로 검증했습니다.
> ① **JUnit 동시성 테스트**: 100스레드가 같은 좌석에 동시 홀드 → 정확히 1명만 성공함을 자동화로 증명
> ② **k6 부하 테스트**: 수천 VU가 좌석 선점을 시도할 때 응답시간·에러율이 어떻게 변하는지 측정
> 전자는 correctness, 후자는 capacity를 본 것입니다."

### Q. "부하 테스트와 단위 테스트의 차이가 뭔가요?"
> "단위 테스트는 'PASS or FAIL' 이진 답을 주고, 부하 테스트는 '얼마나 빠른가/얼마나 견디는가'에 대한 **숫자**를 줍니다.
> 단위 테스트는 매 커밋마다 CI에서 돌고, 부하 테스트는 릴리스 전이나 인프라 변경(스케일아웃 등) 전에 돌립니다.
> 이 프로젝트는 둘 다 갖추고 있습니다."

---

## 5. 포트폴리오에 어떻게 배치하나?

```
포트폴리오 (3가지 축으로 동시에 어필)

  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
  │  설계 / 아키텍처 │  │   correctness   │  │   performance   │
  │                 │  │                 │  │                 │
  │  docs/          │  │  test-code/     │  │  load-tests/    │
  │  - JWT 인증     │  │  - 23 tests     │  │  - knee point   │
  │  - 좌석 락 설계 │  │  - 동시성 검증  │  │  - p95 latency  │
  │  - Saga 보상   │  │  - ArchUnit     │  │  - RPS 한계     │
  └─────────────────┘  └─────────────────┘  └─────────────────┘
```

**셋이 서로 보완**한다는 점이 핵심:
- 설계만 있으면 → "코드로 증명되었나?"
- 테스트만 있으면 → "현실 부하에서도 동작하나?"
- 부하 테스트만 있으면 → "정확성은?"

---

## 6. 포트폴리오에 적기 좋은 MD 파일 추천 (이 프로젝트 기준)

면접에서 **"이거 한 번 읽어봐 주세요"** 라고 자신 있게 내밀 수 있는 문서들을 우선순위로 정리한다.

### ⭐⭐⭐ Tier 1 — 반드시 보여줘야 할 것
| 파일 | 무엇을 보여주나 |
|------|----------------|
| `docs/backend-portfolio.md` | 프로젝트 개요·아키텍처·핵심 구현·결과를 한 페이지로 요약 |
| `docs/decisions.md` | 5가지 핵심 기술 결정 (락, Kafka, DB락, 멱등, Virtual Thread) — **why 중심** |
| `docs/jwt-auth.md` | JWT 4-case 재발급, family 탈취 감지, 단일 트랜잭션 — 분산 환경 의식 |
| `test-code/05-test-catalog.md` | 23개 테스트 메서드 카탈로그 표 |
| `test-code/07-bugs-found-via-testing.md` | 실제 발견·수정한 버그 3건 (Testcontainers / JWT 트랜잭션 / nginx 로그) |
| `test-code/evidence/README.md` + `images/` | 23 tests 100% pass 증거 (HTML 리포트 + 동시성 콘솔) |
| `docs/load-test-portfolio.md` | k6 부하 테스트 결과 (knee point, p95, RPS) |

### ⭐⭐ Tier 2 — 깊이 있는 질문 들어왔을 때 꺼낼 것
| 파일 | 무엇을 보여주나 |
|------|----------------|
| `docs/sequence-diagrams.md` | 홀드·결제·Saga 보상 시퀀스 |
| `docs/resilience-ops.md` | 서킷브레이커·Redis 장애 시 fallback 동작 |
| `docs/monitoring.md` | Prometheus 커스텀 메트릭, Golden Signals |
| `test-code/01-test-strategy.md` | 테스트 피라미드 + 설계 결정 |
| `test-code/06-interview-qa.md` | 면접 답변 스크립트 (본인용 준비 자료) |

### ⭐ Tier 3 — 보조 자료
| 파일 | 무엇을 보여주나 |
|------|----------------|
| `docs/data.md` | Redis 키 구조, Kafka 토픽 |
| `docs/infra.md` | 스케줄러, 설정값 |
| `docs/deployment-ec2.md` | 인프라 구성, 스케일아웃 체크리스트 |
| `test-code/02-test-types-and-conventions.md` | 테스트 작성 컨벤션 |

### 면접 시 권장 노출 순서
1. `backend-portfolio.md` — 첫 30초로 전체 그림 잡기
2. `decisions.md` — 1~2분으로 핵심 의사결정 어필
3. 질문 따라 분기:
   - **"테스트 작성?"** → `test-code/05-test-catalog.md` + `evidence/Test-Summary.png`
   - **"동시성?"** → `SeatHoldConcurrencyTest.png` + `decisions.md`
   - **"부하?"** → `load-test-portfolio.md`
   - **"버그 발견 경험?"** → `test-code/07-bugs-found-via-testing.md`
   - **"JWT?"** → `docs/jwt-auth.md`
