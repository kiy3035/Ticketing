# my-docs — 상세 정리 & 공부용

이 폴더는 **프로젝트 소스와 전체 워크플로우를 이해·복기**하기 위한 긴 글 모음이다.  
면접용 한 장 요약은 [`docs/`](../docs/) 와 [`interview/`](../interview/) 에 두고, 여기서는 **클래스·메서드·Redis 키·트랜잭션 경계**까지 손으로 따라갈 수 있게 썼다.

## 문서 목차

| 문서 | 내용 |
|------|------|
| [01-full-workflow.md](01-full-workflow.md) | 사용자 시나리오 → 화면 → API → 서비스 → DB/Redis/Kafka 순서로 한 번에 따라가기 |
| [02-source-structure.md](02-source-structure.md) | 패키지별 역할, 핵심 클래스, 호출 관계 |
| [03-hold-lock-and-reservation.md](03-hold-lock-and-reservation.md) | 홀드 생성 → 예약 확정 → **Outbox + AFTER_COMMIT** 까지 트랜잭션 경계 |
| [04-payment-and-refund.md](04-payment-and-refund.md) | request → approve → complete, 보상 처리, 공연 취소 환불 배치 |
| [05-schedulers.md](05-schedulers.md) | **스케줄러 5종**: 대기열 입장·정리, 홀드 만료, 환불, **Kafka Outbox 발행** |
| [06-redis-kafka-reference.md](06-redis-kafka-reference.md) | Redis 키 전체, Rate limit Lua, **`kafka_outbox`**, Kafka 토픽·DLT·VT — *구 `redis-patterns`·`kafka-consumer-guide` 통합본* |
| [07-oauth2-login.md](07-oauth2-login.md) | Google OAuth2, JIT 가입, `internal_username`, 코드 위치 |
| [concurrency-deep-dive.md](concurrency-deep-dive.md) | SETNX·Lua·DB 비관적 락, 부하 테스트 아이디어 |
| [resilience-patterns.md](resilience-patterns.md) | 멱등 키, 보상, 서킷브레이커(개념), 레이트 리밋, **Outbox** |
| [virtual-threads-guide.md](virtual-threads-guide.md) | Virtual Thread 적용 지점(Tomcat, 스케줄러 내부, Kafka 리스너) |
| [flyway-guide.md](flyway-guide.md) | 마이그레이션 운영 팁 |
| [load-test-guide.md](load-test-guide.md) | k6·데이터 시드·실행 순서 |
| [troubleshooting.md](troubleshooting.md) | 개발 중 이슈 로그(직렬화, Kafka, Flyway 등) |

## docs/ 와의 역할 나눔

- **`docs/`**: 포트폴리오·배포·API·아키텍처 요약. [`decisions.md`](../docs/decisions.md), [`sequence-diagrams.md`](../docs/sequence-diagrams.md) 가 설계 결정과 시퀀스의 기준이다.
- **`my-docs/`**: 위를 **코드 라인 단위로 펼친** 공부 노트. 중복을 없애기 위해 Redis·Kafka 상세는 **`06-redis-kafka-reference.md` 한 파일**로 모았다.
