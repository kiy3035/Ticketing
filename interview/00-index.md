# 인터뷰 가이드

면접관이 이 포트폴리오를 보고 물어볼 만한 질문과 답변을 **문답** 형태로 정리했습니다.  
눈높이는 **백엔드 4년차** — 설계 의도, 트레이드오프, 실패 시나리오, 운영 관점까지 말할 수 있다고 가정합니다.  
코드·문서와 불일치하면 **소스와 [docs/sequence-diagrams.md §5](../docs/sequence-diagrams.md#consistency-failure-scenarios)** 를 기준으로 합니다.

| 파일 | 주제 |
|------|------|
| [01-architecture.md](01-architecture.md) | 레이어, Kafka·outbox, AFTER_COMMIT, 스케일아웃, 아쉬운 점 |
| [02-concurrency-and-lock.md](02-concurrency-and-lock.md) | Redis 락, Lua, TTL, 레이스, 이중 방어 |
| [03-redis-and-data-model.md](03-redis-and-data-model.md) | 키 설계, ZSet+String, TTL·LRU, 네임스페이스 |
| [04-queue-and-traffic.md](04-queue-and-traffic.md) | 대기열, 백프레셔, 복잡도, 병목·knee point |
| [05-transaction-and-consistency.md](05-transaction-and-consistency.md) | 결제·예약 경계, outbox, 멱등, 환불 배치, 강한/최종 일관성 |
| [06-infra-and-operations.md](06-infra-and-operations.md) | EC2·Docker, 스케줄러·락, 메트릭, 배포 |
| [07-testing-and-quality.md](07-testing-and-quality.md) | Testcontainers, 동시성 테스트, ArchUnit, 멱등 AOP |
| [09-my-level-answer-guide.md](09-my-level-answer-guide.md) | 내 수준 맞춤 답변 템플릿, Saga/CB 실전 답변 |
