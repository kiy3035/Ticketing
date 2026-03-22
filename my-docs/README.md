# my-docs — 상세 정리 & 공부용

이 폴더는 **내가 프로젝트 소스와 전체 워크플로우를 이해하기 위한** 상세 정리입니다.  
면접관용이 아니라, 코드 읽기·흐름 따라가기·설계 이유 복기용입니다.

## 📂 문서 목차

| 문서 | 내용 |
|------|------|
| [01-full-workflow.md](01-full-workflow.md) | **전체 워크플로우**: 사용자 시나리오 → 화면 → API 호출 순서 → 서비스/DB/Redis 흐름 한 번에 따라가기 |
| [02-source-structure.md](02-source-structure.md) | **소스 구조**: 패키지별 역할, 핵심 클래스가 뭘 하는지, 어디서 호출되는지 |
| [03-hold-lock-and-reservation.md](03-hold-lock-and-reservation.md) | **홀드·락·예약 확정**: 좌석 락 → 홀드 생성 → 결제 완료 시 예약 확정 → DB 커밋 후 홀드 해제 (트랜잭션 경계 포함) |
| [04-payment-and-refund.md](04-payment-and-refund.md) | **결제·환불**: request → approve → complete, 공연 취소 시 환불 배치 순서, POINT/CARD 차이 |
| [05-schedulers.md](05-schedulers.md) | **스케줄러 4종**: 대기열 입장 허용, 대기열 정리, 홀드 만료 정리, 취소 공연 환불 — 언제 무엇을 하는지 |
| [06-redis-kafka-reference.md](06-redis-kafka-reference.md) | **Redis·Kafka 참고**: 키 이름, ZSet/String 용도, Kafka 토픽·이벤트 타입 (docs/data.md 요약 + 코드 위치) |
| [07-oauth2-login.md](07-oauth2-login.md) | **OAuth2(Google)**: 용어, Authorization Code 흐름, JIT 가입, `internal_username` 이유, 코드 위치, 삭제 시 주의 |

## 🔗 docs/ 와의 관계

- **docs/** : 포트폴리오·면접용 요약 (아키텍처, API 명세, 인프라 등)
- **my-docs/** : 위 문서들을 바탕으로 **코드와 흐름을 이해**하기 위한 상세·공부용 정리

겹치는 내용은 docs를 참고하라고만 적어 두고, my-docs에서는 "어느 클래스·어느 메서드에서 그렇게 동작하는지" 위주로 썼습니다.
