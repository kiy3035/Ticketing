# 대기열 / 트래픽

---

### 🟢 Q1. 왜 대기열 시스템이 필요하다고 판단하셨나요?

**A.** 콘서트 예매는 **오픈 순간 트래픽이 폭증** 하는 스파이크 패턴이라, 모든 요청이 동시에 좌석 홀드/결제까지 도달하면 Redis 락 경합과 DB 커넥션 포화가 발생합니다. 대기열은 "좌석 페이지까지 동시에 들여보낼 사용자 수" 를 제어하는 **백프레셔(backpressure)** 역할입니다. `QueueProcessingScheduler` 가 2초마다 상위 N명만 입장 허용해 다운스트림(홀드/결제) 부하를 인프라가 감당할 수준으로 조절합니다.

> **🟢 Q1-1. 대기열이 항상 필요한 건 아닌데, 평상시에도 거쳐야 하나요?**
> **A.** 아닙니다. **패턴 B (조건부 대기열)** 입니다.
> - `GET /api/queue/required?concertId=...` 가 `queueService.countWaiting()` 으로 현재 대기 인원 확인
> - `ticketing.queue.activation-threshold`(기본 50) 초과해야 `required=true`
> - false면 프론트가 대기열 페이지 스킵 → 바로 좌석 페이지로
>
> 또 `QueueController.enter()` 에서도 `immediate-allow-threshold`(기본 30) 이하 + 가용 좌석 ≥ 대기 인원이면 진입 즉시 `allowEntry()` 호출해 바로 입장.

---

### 🟡 Q2. 대기열의 Redis 자료구조 선택과 시간 복잡도?

**A.** ZSet (`queue:concert:{concertId}`):
- 멤버: UUID 토큰
- score: 진입 시각(ms)
- 순번 조회: `ZRANK` O(log N)
- 대기인원: `ZCARD` O(1)
- 상위 N명: `ZRANGE 0 N-1` O(log N + M)

수만 명이 대기열에 있어도 각 연산이 밀리초 이내. 토큰 메타데이터는 별도 String 키.

> **🟡 Q2-1. `enterQueue()` 의 `removeExistingTokens()` 가 O(N) 스캔인데 성능 문제는?**
> **A.** 현재 `redisTemplate.opsForZSet().range(queueKey, 0, -1)` 로 전체 조회 후 사용자 일치 토큰을 찾아 제거합니다. 대기열이 수만 건일 때 병목 가능. 개선 방향은 `queue:user:{concertId}:{userId}` 역인덱스 키로 O(1) 회수하는 것 — **인지하고 있는 개선 포인트**입니다.

---

### 🟡 Q3. `QueueProcessingScheduler` 의 입장 허용 로직?

**A.** 2초마다 (`fixedDelay = ticketing.queue.processing-interval-ms`) 실행:
1. `lockService.tryLock("lock:batch:queue-process", 15s)` — 멀티 인스턴스 중 한 노드만
2. `concertRepository.findAll()` 로 모든 공연 순회
3. 공연별로:
   - `availableSeats = totalSeats - reservedCount`
   - `allowCount = totalSeats == 0 ? batchSize : min(batchSize, availableSeats)`
4. `queueService.getTopTokens(concertId, batchSize)` — ZSet 상위 N개
5. 토큰별로 이미 허용 안됐는지·토큰 메타 존재·concertId 일치 확인 후 `queueService.allowEntry(token, concertId)`

> **🟡 Q3-1. `findAll()` 로 모든 공연 순회는 비효율 아닌가요?**
> **A.** 맞습니다. 현재는 대기열이 활성화된 공연만 필터하는 최적화가 없습니다. 개선 방향은 (a) 대기열에 1명 이상 있는 concertId 목록을 Redis 별도 Set 으로 관리, (b) `QueueService.activeConcertIds()` 같은 메서드 추가 — **다음 개선 우선순위로 두고 있습니다.**

---

### 🟡 Q4. 만료 토큰 정리(`QueueCleanupScheduler`)는 어떻게?

**A.** `queue:token:{token}` String 은 TTL 로 자동 만료되지만, ZSet (`queue:concert:{concertId}`) 에는 멤버별 TTL 이 없어 **유령 멤버** 가 남습니다. `QueueCleanupScheduler` 가 60초마다:
1. `lock:batch:queue-cleanup` 락 획득
2. 콘서트별 `queueService.pruneExpiredTokens(concertId, batchSize=200)` 호출
3. `ZSCAN` 으로 ZSet 순회하면서 `EXISTS queue:token:{token}` 가 false 인 멤버 모아 일괄 `ZREM`

> **🟡 Q4-1. ZSet 자체에 TTL 을 거는 대신 스케줄러로 정리하는 이유는?**
> **A.** ZSet 에 TTL 을 걸면 공연 단위 전체 대기열이 한꺼번에 삭제됩니다. Redis ZSet 은 멤버별 TTL 을 지원하지 않아, 개별 토큰은 String 키 TTL 로 만료시키고 ZSet 은 스케줄러가 정리.

---

### 🟡 Q5. 부하 테스트에서 병목을 어떻게 파악하고, 어떤 순서로 튜닝하나요?

**A.** k6 + Prometheus/Grafana 조합:
1. **knee-point.js** 시나리오로 VU 를 500 → 800 → 1000 → 1200 → 1500 계단식 증가
2. Grafana 에서 RPS 곡선이 평탄해지거나 꺾이는 VU 구간을 knee point 로 식별
3. 그 시점에 어떤 메트릭이 먼저 튀는지로 병목 진단

**관측 지표**:
- `http_req_duration` p95 / `http_req_failed`
- `ticketing_lock_acquire_failures_total` (락 경합)
- `ticketing_hold_conflict_total` (Lua EXISTS 차단)
- `hikaricp_connections_pending` (DB 풀 병목)
- Redis `evicted_keys`, `instantaneous_ops_per_sec`
- Kafka consumer lag, `kafka_outbox` PENDING/FAILED 수
- `jvm_threads_live_threads` (VT 적용 효과)

> **🟡 Q5-1. 병목 확인 후 어떤 순서로 대응?**
> **A.**
> 1. **설정 기반**: `queue.batch-size`, `processing-interval-ms`, `hold.ttl`, `lock.ttl`, HikariCP `maximum-pool-size`, Redis `max-active`
> 2. **읽기 부하 분산**: 콘서트 목록·잔여석 캐시 강화
> 3. **수평 확장**: 앱 인스턴스 수 늘리기 (이미 분산 락·세션 없음으로 준비됨)
> 4. **도메인 분리**: 대기열을 별도 서비스로, Redis Cluster, RDS Read Replica

---

### 🔴 Q6. 대기열 폴링이 서버 부하를 키우지 않나요?

**A.** 키울 수 있어서 두 가지로 완화했습니다.
1. **잔여석 집계 캐시**: `GET /api/queue/status` 가 호출되는 잔여석 계산을 `@Cacheable(QUEUE_STATUS_AVAILABLE_SEATS, key=#concertId)` 로 묶고 TTL 2초. 홀드/예약/만료 시 evict 동기화. 1만 명 동시 폴링도 공연당 0.5 QPS 만 실제 계산.
2. **Redis 서킷브레이커**: `RedisCircuitBreakerExecutor` 로 polling 대상 (`queue.getRank`, `queue.countWaiting`, `queue.isAllowed`) 모두 fast-fail + fallback.

다음 단계로는 **WebSocket/SSE 푸시 모델** 로 polling 자체를 줄이는 게 맞습니다. 알림 채널은 이미 `NotificationSseController` + `SseNotificationService` 로 구현했고 Redis Pub/Sub 브로드캐스트로 다중 인스턴스 환경에서도 동작합니다 (`SseNotificationMultiInstanceIntegrationTest` 검증).

> **🔴 Q6-1. 폴링 주기는 어떻게 정했나요?**
> **A.** 프론트에서 2초 주기. `QueueProcessingScheduler` 의 입장 허용 주기와 맞춰 사용자가 "허용된 직후" 평균 1초 안에 화면 전환되게. 너무 짧으면 서버 부하·너무 길면 사용자 경험 저하 — 트레이드오프.
