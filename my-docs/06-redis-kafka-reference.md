# 06. Redis·Kafka 참고 (키·토픽·코드 위치)

코드 읽을 때 "이 키/이 토픽이 어디서 쓰이지?" 찾기 쉽게, **키 이름·용도·주요 사용 클래스**만 정리했다. 상세 스키마·예시는 `docs/data.md` 참고.

---

## 1. Redis 키 요약

| 키 패턴 | 타입 | 용도 | 사용처 (대표) |
|---------|------|------|----------------|
| queue:concert:{concertId} | ZSet | 콘서트별 대기열 (멤버=토큰, 스코어=진입 시각) | QueueService |
| queue:token:{token} | String(JSON) | 토큰별 userId, concertId, enteredAt | QueueService |
| queue:allowed:{token} | String(JSON) | 입장 허용 여부 | QueueService, QueueController |
| hold:seat:{seatId} | String | 좌석별 홀드 토큰 | HoldStore |
| hold:token:{holdToken} | String(JSON) | 홀드 상세(HoldInfo) | HoldStore |
| hold:expires | ZSet | 만료 시각 기준 홀드 스캔용 | HoldStore, HoldCleanupScheduler |
| hold:user:{userId} | Set | 사용자별 홀드 토큰 목록 | HoldStore |
| lock:seat:{seatId} | String | 좌석 락 (값=UUID) | RedisLockService |
| lock:batch:queue-process | String | 대기열 입장 배치 락 | QueueProcessingScheduler |
| lock:batch:queue-cleanup | String | 대기열 만료 토큰 정리 배치 락 | QueueCleanupScheduler |
| lock:batch:hold-cleanup | String | 홀드 만료 배치 락 | HoldCleanupScheduler |
| lock:batch:refund | String | 환불 배치 락 | RefundForCancelledConcertScheduler |
| spring:session:* | — | 세션 | Spring Session |
| (캐시 키) | — | 콘서트 목록 등 | CacheKeyConfig, @Cacheable |

---

## 2. Kafka 토픽·이벤트

| 토픽 (설정) | 이벤트 타입 | 발행처 | 소비처 (대표) |
|-------------|-------------|--------|----------------|
| ticketing.seat-hold-events | HOLD_CREATED, HOLD_CANCELED, HOLD_EXPIRED, RESERVATION_CONFIRMED | SeatHoldEventPublisher, ReservationConfirmedEventListener | SeatHoldEventConsumer → 알림 저장, SSE |
| ticketing.payment-complete | 결제 완료 이벤트 | PaymentCompleteEventPublisher | PaymentCompleteEventConsumer → 이메일/SMS 등 |

- **RESERVATION_CONFIRMED**는 DB 커밋 **후** ReservationConfirmedEventListener에서만 발행된다 (confirm() 안에서 아님).
- 토픽 이름·설정: TicketingProperties 또는 application.properties의 kafka 관련 키.

---

## 3. 코드에서 찾을 때

- **Redis 키 문자열**: `HoldStore`(hold:), `QueueService`(queue:), `RedisLockService`(lock:)
- **Kafka 발행**: SeatHoldEventPublisher, PaymentCompleteEventPublisher
- **Kafka 소비**: SeatHoldEventConsumer, PaymentCompleteEventConsumer

상세 페이로드·컨슈머 그룹은 `docs/data.md` 참고.
