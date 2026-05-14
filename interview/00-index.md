# 인터뷰 가이드

면접관이 이 포트폴리오를 보고 물어볼 만한 질문과 답변을 **문답** 형태로 정리.
백엔드 + 인프라 중심으로, **난이도 하 → 중 → 상** 순서로 점진적으로 진행되도록 구성했다.

각 질문에 `🟢 하` / `🟡 중` / `🔴 상` 표시. 코드·문서와 불일치하면 **소스 + [docs/ticketing-portfolio.md](../docs/ticketing-portfolio.md)** 가 기준.

| 파일 | 주제 |
|------|------|
| [01-architecture.md](01-architecture.md) | 레이어, Kafka·Outbox, AFTER_COMMIT, 스케일아웃 |
| [02-concurrency-and-lock.md](02-concurrency-and-lock.md) | Redis 락, Lua, TTL, 레이스, 이중 방어 |
| [03-redis-and-data-model.md](03-redis-and-data-model.md) | 키 설계, ZSet+String, TTL·LRU, 네임스페이스 |
| [04-queue-and-traffic.md](04-queue-and-traffic.md) | 대기열, 백프레셔, 복잡도, 병목·knee point |
| [05-transaction-and-consistency.md](05-transaction-and-consistency.md) | 트랜잭션 경계, Outbox, Saga(REQUIRES_NEW), 멱등, 일관성 |
| [06-infra-and-operations.md](06-infra-and-operations.md) | EC2·Docker·nginx, 스케줄러·락, 헬스체크, 배포, 모니터링 |
| [07-testing-and-quality.md](07-testing-and-quality.md) | Testcontainers, 동시성 테스트, ArchUnit, 멱등 AOP |
| [08-jwt-auth.md](08-jwt-auth.md) | JWT Access/Refresh, jti 기반 폐기, Redis 블랙리스트 |
| [09-my-level-answer-guide.md](09-my-level-answer-guide.md) | 답변 템플릿, Saga/CB/Outbox 실전 답변 |

## 답변 전략 (모든 질문에 공통)
1. **문제** — 어떤 장애·리스크를 줄이려 했는가
2. **선택** — 왜 그 방식인가 (대안과의 트레이드오프)
3. **결과** — 코드/운영에서 실제 효과
4. **한계** — 미완성·다음 개선 포인트
