# 인프라 & 환경 설정

## 스케줄러 (5종)

| 스케줄러 | 주기 | 용도 | 분산 락 |
|----------|------|------|---------|
| `QueueProcessingScheduler` | 2초 | 대기열 상위 N명 입장 허용 | `lock:batch:queue-process` |
| `QueueCleanupScheduler` | 60초 | 만료 토큰 ZSet 정리 | - |
| `HoldCleanupScheduler` | 60초 | 만료 홀드 스캔·해제·이벤트 발행 | `lock:batch:hold-cleanup` |
| `KafkaOutboxPublishScheduler` | 500ms | DB outbox → Kafka 발행 | `lock:batch:kafka-outbox` |
| `RefundForCancelledConcertScheduler` | 5분 | 취소 공연 완료 결제 환불 | `lock:batch:refund` |

앱 서버 2대에서도 각 배치가 한 인스턴스만 실행되도록 Redis 분산 락 적용. 주기는 모두 `application.properties`로 외부화.

## Transactional Outbox

| 설정 키 | 기본값 | 용도 |
|---------|--------|------|
| `ticketing.outbox.publish-interval-ms` | 500ms | PENDING 행 Kafka 발행 주기 |
| `ticketing.outbox.batch-size` | 50 | 한 번에 처리할 최대 행 수 |
| `ticketing.outbox.max-publish-attempts` | 25 | 초과 시 `FAILED` 표시 → 수동 처리 대상 |

## 헬스체크

- `GET /actuator/health` → `ticketingDatastores` 커스텀 인디케이터 (Redis PING + DB `isValid(2)` 모두 UP이어야)
- Kafka 헬스체크 **비활성화** (`management.health.kafka.enabled=false`) — 부하 시 60초 타임아웃 방지

## 주요 설정값

| 항목 | 기본값 | 비고 |
|------|--------|------|
| Virtual Thread | enabled=true | Java 21 Loom, Tomcat 요청 스레드 |
| Hikari max-pool-size | 30 | 부하 테스트 최적값 |
| Redis max-active | 20, min-idle=5 | Lettuce 풀 |
| 좌석 락 TTL | 3s (부하테스트) / 5s (기본) | `ticketing.lock.ttl-seconds` |
| 홀드 TTL | 300s (부하테스트) / 600s (기본) | `ticketing.hold.ttl-seconds` |
| 대기열 토큰 TTL | 60s (부하테스트) / 1800s (기본) | `ticketing.queue.token-ttl-seconds` |
| 결제 중 홀드 연장 | 1200s (20분) | `ticketing.payment.hold-extension-ttl-seconds` |
| Rate Limit | 10 req/s/user | Sliding Window, `ticketing.rate-limit.*` |
| Circuit Breaker | failure-rate 50%, wait 30s | `redisCircuitBreaker` |
