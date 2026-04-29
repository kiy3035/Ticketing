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

## nginx 위치에 대한 설계 결정

nginx는 앱 서버(t3a.small)가 아닌 **인프라 서버(t3a.medium)**에서 운영한다.

**"nginx가 더 큰 서버에 있어서 결과가 부풀려진 것 아닌가?"** 라는 의문이 생길 수 있다. 결론은 그렇지 않다.

| 항목 | 내용 |
|------|------|
| nginx 역할 | TCP 연결 수락 → upstream으로 바이트 포워딩 (이벤트 드리븐, 리소스 사용 극소) |
| 실제 병목 | 앱 서버의 Java 처리, DB 쿼리, Redis 연산 — nginx가 아님 |
| 서버 스펙 영향 | VU=1500 트래픽에서 nginx CPU 사용률 < 5%. t3a.small에 두어도 결과 동일 |

오히려 인프라 서버는 nginx 외에 **Redis·Kafka·Prometheus·Grafana가 CPU·메모리를 함께 경쟁**하는 환경이다. 부하 테스트 중 Redis(락·캐시·대기열)가 가장 바쁜 상태에서 측정됐으므로 결과를 부풀리는 방향이 아닌 **억제하는 방향**이다. 측정된 수치는 유효하다.

---

## 주요 설정값

| 항목 | 값 | 비고 |
|------|--------|------|
| Virtual Thread | enabled=true | Java 21 Loom, Tomcat 요청 스레드 |
| Hikari max-pool-size | 30 | 부하 테스트 최적값 (Phase 1 실험 도출) |
| Hikari minimum-idle | 5 | 앱서버 2대 × 30 = 60 상시 점유로 RDS max_connections 초과 방지. 평시 총 10개 유지 |
| Redis max-active | 20, min-idle=5 | Lettuce 풀 |
| 좌석 락 TTL | 3s (부하테스트) / 5s (기본) | `ticketing.lock.ttl-seconds` |
| 홀드 TTL | 300s (부하테스트) / 600s (기본) | `ticketing.hold.ttl-seconds` |
| 대기열 토큰 TTL | 60s (부하테스트) / 1800s (기본) | `ticketing.queue.token-ttl-seconds` |
| 결제 중 홀드 연장 | 1200s (20분) | `ticketing.payment.hold-extension-ttl-seconds` |
| Rate Limit | 10 req/s/user | Sliding Window, `ticketing.rate-limit.*` |
| Circuit Breaker | failure-rate 50%, wait 30s | `redisCircuitBreaker` |
