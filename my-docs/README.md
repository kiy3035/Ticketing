# my-docs — 백엔드/인프라 공부 노트

이 폴더는 **내가 내 프로젝트의 모든 부분을 코드 라인 단위로 복기**하기 위한 공부 노트다.  
면접용 한 장 요약은 [`docs/`](../docs/) 에 두고, 여기서는 **클래스·메서드·Redis 키·트랜잭션 경계**까지 손으로 따라갈 수 있게 정리했다.

## 문서 목차

| 문서 | 내용 |
|------|------|
| [01-full-workflow.md](01-full-workflow.md) | 사용자 시나리오 → API → 서비스 → DB/Redis/Kafka 한 번에 따라가기 |
| [02-source-structure.md](02-source-structure.md) | 패키지별 역할, 핵심 클래스, 호출 관계 |
| [03-hold-lock-and-reservation.md](03-hold-lock-and-reservation.md) | 홀드 생성 → 예약 확정 → **Outbox + AFTER_COMMIT** 트랜잭션 경계 |
| [04-payment-and-refund.md](04-payment-and-refund.md) | request → approve → complete, **Saga 보상(REQUIRES_NEW)**, 환불 배치 |
| [05-schedulers.md](05-schedulers.md) | **스케줄러 5종**: 대기열 입장·정리, 홀드 만료, 환불, **Outbox 발행** |
| [06-redis-kafka-reference.md](06-redis-kafka-reference.md) | Redis 키 카탈로그, Lua, **`kafka_outbox`**, Kafka 토픽·DLT·VT |
| [08-jwt-auth.md](08-jwt-auth.md) | JWT Access/Refresh, **Refresh jti DB 저장·폐기**, Redis Access 블랙리스트 |
| [concurrency-deep-dive.md](concurrency-deep-dive.md) | SETNX·Lua·DB 비관적 락, 동시성 테스트 |
| [resilience-patterns.md](resilience-patterns.md) | **멱등 키·Saga·서킷브레이커·Rate Limit·Outbox** 종합 |
| [virtual-threads-guide.md](virtual-threads-guide.md) | Virtual Thread 적용 지점(Tomcat, 스케줄러, Kafka 리스너) |
| [flyway-guide.md](flyway-guide.md) | 마이그레이션 운영 팁 (V1~V8 현황 포함) |
| [load-test-guide.md](load-test-guide.md) | k6 스크립트 실행 커맨드 |
| [troubleshooting.md](troubleshooting.md) | 개발 중 이슈 로그 |

## docs/ 와의 역할 분리

- **`docs/`**: 포트폴리오·배포·API·아키텍처 요약. [`ticketing-portfolio.md`](../docs/ticketing-portfolio.md)의 ADR 섹션과 [`sequence-diagrams.md`](../docs/sequence-diagrams.md) 가 설계 결정과 시퀀스의 기준이다.
- **`my-docs/`**: 위를 **코드 라인 단위로 펼친** 공부 노트. Redis·Kafka 상세는 **`06-redis-kafka-reference.md` 한 파일**로 모았다.
