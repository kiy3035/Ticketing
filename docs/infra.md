# 인프라 & 환경 설정

## Docker Compose 서비스

| 서비스 | 이미지 | 포트 | 용도 | 메모리 제한 |
|--------|--------|------|------|------------|
| **zookeeper** | confluentinc/cp-zookeeper:7.6.1 | 2181 | Kafka 메타데이터 관리 | 256MB |
| **kafka** | confluentinc/cp-kafka:7.6.1 | 9092, 29092 | 이벤트 스트리밍 (단일 브로커) | 512MB |
| **kafka-ui** | provectuslabs/kafka-ui | 8081 | Kafka 관리 웹 UI | 256MB |
| **redis** | redis:7.2-alpine | 6379 | 세션/홀드/락/캐시/대기열 | 512MB (사용 400MB) |
| **redisinsight** | redis/redisinsight | 5540 | Redis 관리 웹 UI | 256MB |

Redis 옵션: `--maxmemory 400mb --maxmemory-policy allkeys-lru --save ""`

## 스케줄러 (5종)

| 스케줄러 | 설정 키 | 기본 주기 | 용도 |
|----------|---------|----------|------|
| **QueueProcessingScheduler** | `ticketing.queue.processing-interval-ms` | 2초 | 대기열 상위 N명 입장 허용 |
| **QueueCleanupScheduler** | `ticketing.queue.cleanup-interval-ms` | 60초 | 만료 토큰 ZSet 정리 |
| **HoldCleanupScheduler** | `ticketing.hold.cleanup-interval-ms` | 60초 | 만료 홀드 스캔·해제·이벤트 발행 |
| **RefundForCancelledConcertScheduler** | `ticketing.refund.interval-ms` | 5분 | 취소 공연 COMPLETED 결제 환불 |
| **KafkaOutboxPublishScheduler** | `ticketing.outbox.publish-interval-ms` | 500ms | DB outbox → Kafka (`RESERVATION_CONFIRMED` 등) |

모든 주기는 `application.properties` 또는 환경 변수로 변경 가능하다.

### Kafka transactional outbox

| 설정 키 | 기본값 | 용도 |
|---------|--------|------|
| `ticketing.outbox.publish-interval-ms` | 500 | PENDING 행을 Kafka 로 발행하는 주기 |
| `ticketing.outbox.batch-size` | 50 | 한 번에 처리할 최대 행 수 |
| `ticketing.outbox.max-publish-attempts` | 25 | 초과 시 행을 `FAILED` 로 표시 (수동 조치 대상) |

예약 확정 시 `RESERVATION_CONFIRMED` 는 예약 DB 커밋과 동일 트랜잭션에서 `kafka_outbox` 에 적재되고, 위 스케줄러가 비동기로 토픽에 발행한다.

DB·Redis·Kafka 가 어긋날 때 남는 상태, 재시도, 사용자 오류 응답은 [sequence-diagrams.md §5](sequence-diagrams.md#consistency-failure-scenarios) 표·시퀀스를 참고한다.

운영·JVM 관점 요약은 [java-ops.md](java-ops.md) 참고.

## 주요 설정

### 대기열

| 설정 키 | 기본값 | 용도 |
|---------|--------|------|
| `ticketing.queue.batch-size` | 50 | 한 번에 입장 허용할 사용자 수 |
| `ticketing.queue.token-ttl-seconds` | 1800 | 대기열 토큰 만료 시간 (초) |
| `ticketing.queue.cleanup-batch-size` | 200 | 한 번에 정리할 토큰 수 |
| `ticketing.queue.activation-threshold` | (설정) | 대기열 유동 활성화 기준 인원 |

### 홀드

| 설정 키 | 기본값 | 용도 |
|---------|--------|------|
| `ticketing.hold.ttl-seconds` | 600 | 홀드 유지 시간 (10분) |
| `ticketing.hold.cleanup-batch-size` | 200 | 한 번에 정리할 만료 홀드 수 |
| `ticketing.kafka.hold-topic` | `ticketing.seat-hold-events` | Kafka 토픽 이름 |

### 락

| 설정 키 | 기본값 | 용도 |
|---------|--------|------|
| `ticketing.lock.ttl-seconds` | 5 | 좌석 락 TTL |
| `ticketing.lock.retry-count` | 0 | 락 획득 재시도 횟수 |
| `ticketing.lock.retry-delay-ms` | 50 | 재시도 간 대기 시간 |

### 환불 배치

| 설정 키 | 기본값 | 용도 |
|---------|--------|------|
| `ticketing.refund.batch-size` | 50 | 콘서트별 한 번에 처리할 결제 건수 |
| `ticketing.refund.interval-ms` | 300000 | 배치 실행 주기 (5분) |

### 인프라 연결

| 설정 키 | 기본값 | 비고 |
|---------|--------|------|
| `REDIS_HOST` / `REDIS_PORT` | localhost / 6379 | Lettuce 풀: max-active=20, min-idle=5 |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | acks=all, idempotence=true |
| `DB_URL` | jdbc:mysql://localhost:3306/ticketing | HikariCP 기본 풀 |
| 세션 TTL | 30분 | `spring.session.store-type=redis` |

## 장애 대응

| 증상 | 확인 | 대응 |
|------|------|------|
| Redis 연결 실패 | `redis-cli ping` | 컨테이너 재시작, 메모리 한도 확인 |
| Kafka 연결 실패 | Kafka UI 또는 `kafka-topics --list` | Zookeeper/Kafka 로그 확인 |
| 메모리 부족 | `docker stats`, Redis `INFO memory` | TTL 단축, maxmemory 조정 |
| 세션 이상 | RedisInsight에서 `ticketing:sessions:*` TTL 확인 | `session.timeout` 값 점검 |
