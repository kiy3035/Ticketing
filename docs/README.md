# 콘서트 티켓 예매 시스템 — 백엔드 포트폴리오

> 동시 1,000명 이상 접속 환경에서 좌석 중복 예약 0건을 보장하는 콘서트 예매 백엔드.
> Redis 분산 락 · Kafka · Transactional Outbox · Saga 보상 패턴을 직접 구현했고, **부하 테스트로 병목을 진단해 p95를 2.06s → 164ms로 92% 단축**한 트러블슈팅 사례를 보유합니다.

## 가장 자랑하고 싶은 3가지

1. **DB 병목 진단 → 캐시 도입**: 풀 크기·Virtual Thread로도 풀리지 않던 병목을 폴링 쿼리 캐시화로 해결. p95 ▼78%, RPS ▲122%.
2. **분산 락 정확성 증명**: 100 VU 동시 좌석 선점에서 201 응답 정확히 1건. 2대 nginx 분산 환경에서도 동일하게 좌석 중복 예약 0건 불변식을 검증.
3. **Knee Point 측정**: VU=800에서 1,447 RPS·에러 0%. VU=1,000~1,200을 변곡점으로 식별하고 안정 운영 SLO 수립.

---

## 문서 구조 (읽는 순서 추천)

| 순서 | 문서 | 내용 | 분량 |
|------|------|------|------|
| **1** | [**backend-portfolio**](backend-portfolio.md) | **메인** — 아키텍처 + 트러블슈팅 4가지 사례 + ADR | 핵심 |
| 2 | [load-test-portfolio](load-test-portfolio.md) | 부하 테스트 7개 Phase — 가설·실험·해석 |  데이터 |
| 3 | [jwt-auth](jwt-auth.md) | JWT 4-case 재발급 + family 기반 탈취 감지 | 보조 |
| 4 | [sequence-diagrams](sequence-diagrams.md) | 좌석 선점 · 결제 + 예약 · Saga 보상 시퀀스 | 시각 |

### 부록 (참조)

| 문서 | 내용 |
|------|------|
| [data](data.md) | Redis 키 구조, Kafka 토픽/이벤트 |
| [infra](infra.md) | 스케줄러 5종, Outbox 설정, 주요 설정값 |
| [monitoring](monitoring.md) | Prometheus 커스텀 메트릭, Golden Signals PromQL |
| [deployment-ec2](deployment-ec2.md) | 인프라 구성, 스케일아웃 체크리스트 |

---

## 핵심 수치 한눈에

| 구성 | VU | p95 | RPS | 에러율 |
|------|----|-----|-----|--------|
| 1대 기준선 (pool=10) | 800 | 1.93s | ~408/s | 0% |
| **1대 + 잔여석 캐시** | 800 | **444ms** | **~834/s** | **0%** |
| **2대 nginx 분산** | 800 | **164ms** | **~1,447/s** | **0%** |
| 동시 좌석 선점 (정확성) | 100 | - | - | **201: 1건** |
