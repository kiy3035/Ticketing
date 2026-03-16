# 인터뷰 가이드

면접관이 이 포트폴리오를 보고 물어볼 만한 질문과 답변을, **꼬리 질문** 형태로 정리한 곳입니다.
눈높이는 **백엔드 4년차** 기준이며, 답변에는 실제 클래스·메서드명을 포함합니다.

| 파일 | 주제 |
|------|------|
| [01-architecture.md](01-architecture.md) | 레이어 설계, Kafka 도입 이유, AFTER_COMMIT 패턴, 스케일아웃 |
| [02-concurrency-and-lock.md](02-concurrency-and-lock.md) | Redis 분산 락, Lua 원자성, TTL 트레이드오프, 이중 방어 |
| [03-redis-and-data-model.md](03-redis-and-data-model.md) | 키 설계, ZSet+String 분리, TTL/메모리 정책, 네임스페이스 |
| [04-queue-and-traffic.md](04-queue-and-traffic.md) | 대기열 필요성, ZSet 시간복잡도, 패턴 B, 병목 분석 |
| [05-transaction-and-consistency.md](05-transaction-and-consistency.md) | 결제→예약 트랜잭션, 일관성 경계, 환불 배치 멱등성 |
| [06-infra-and-operations.md](06-infra-and-operations.md) | 인프라 스펙, Docker 리소스, 모니터링, CI/CD |
