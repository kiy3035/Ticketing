# 대기열 / 트래픽

---

### Q1. 왜 대기열 시스템이 필요하다고 판단하셨나요?

**A.** 콘서트 예매는 오픈 순간 트래픽이 폭증하는 스파이크 패턴이라, 모든 요청이 동시에 좌석 홀드/결제까지 도달하면 Redis 락 경합과 DB 커넥션 포화가 발생합니다. 대기열은 "좌석 페이지까지 동시에 들여보낼 사용자 수"를 제어하는 백프레셔 역할을 합니다. `QueueProcessingScheduler`가 2초마다 `QueueService.getTopTokens(concertId, batchSize)`로 상위 N명만 `queue:allowed:{token}`에 입장 허용을 부여해, 이후 홀드/결제 구간의 동시 요청을 인프라가 감당할 수준으로 조절합니다.

> **Q1-1. 대기열이 항상 필요한 건 아닌데, 평상시에도 대기열을 거쳐야 하나요?**
> **A.** 아닙니다. `QueueController.required()`가 `GET /api/queue/required?concertId=...`를 받으면 `queueService.countWaiting(concertId)`로 현재 대기 인원을 확인하고, `ticketing.queue.activation-threshold`(기본 50)를 초과할 때만 `required=true`를 반환합니다. 프론트는 이 값이 false면 대기열 페이지 없이 바로 `/concert.html`로 이동합니다. 또한 `QueueController.enter()`에서도 `ticketing.queue.immediate-allow-threshold`(기본 30) 이하이고 가용 좌석이 있으면 진입 즉시 `queueService.allowEntry()`를 호출해 바로 입장시킵니다.

---

### Q2. 대기열의 Redis 자료구조 선택과 시간 복잡도를 설명해 주세요.

**A.** `QueueService`에서 ZSet(`queue:concert:{concertId}`)을 사용합니다. 멤버는 UUID 토큰, score는 진입 시각(밀리초)입니다. 순번 조회는 `ZRANK` O(log N), 대기인원은 `ZCARD` O(1), 상위 N명은 `ZRANGE 0 N-1` O(log N + M)으로, 수만 명이 대기열에 있어도 각 연산이 밀리초 이내에 끝납니다. 토큰 메타데이터(userId, concertId, enteredAt)는 `queue:token:{token}` String에 별도 저장해, ZSet 멤버 크기를 UUID(36바이트)로 일정하게 유지합니다.

> **Q2-1. `enterQueue()`에서 기존 토큰을 제거하는 `removeExistingTokens()`가 O(N) 스캔인데, 성능 문제는 없나요?**
> **A.** 현재 구현은 `redisTemplate.opsForZSet().range(queueKey, 0, -1)`로 전체를 조회합니다. 대기열이 수만 건일 때는 병목이 될 수 있고, 개선 방향으로 userId→token 역인덱스 키(`queue:user:{concertId}:{userId}`)를 두면 O(1)로 기존 토큰을 찾아 제거할 수 있습니다. 이 부분은 인지하고 있는 개선 포인트입니다.

---

### Q3. `QueueProcessingScheduler`의 입장 허용 로직을 설명해 주세요.

**A.** 2초마다 `processQueue()`가 실행되며, 먼저 `lockService.tryLock("lock:batch:queue-process", 15s)`로 분산 락을 잡아 다중 인스턴스에서 한 노드만 실행되게 합니다. 그다음 `concertRepository.findAll()`로 모든 공연을 순회하며, 각 공연의 가용 좌석 수(`totalSeats - reservedCount`)와 `ticketing.queue.batch-size`(기본 50) 중 작은 값만큼만 입장을 허용합니다. 상위 토큰에 대해 `queueService.isAllowed(token)`으로 이미 허용됐는지 확인하고, 아니면 `queueService.allowEntry(token, concertId)`로 `queue:allowed:{token}` 키를 설정합니다.

> **Q3-1. `findAll()`로 모든 공연을 순회하면 공연 수가 많을 때 느리지 않나요?**
> **A.** 현재는 대기열이 활성화된 공연만 필터하는 최적화가 없어서, 공연 수가 많으면 불필요한 순회가 생깁니다. 개선 방향은 대기열에 1명 이상 있는 concertId 목록을 Redis에서 관리하거나, `QueueService`에서 `countWaiting() > 0`인 공연만 반환하는 메서드를 추가하는 것입니다.

---

### Q4. 만료 토큰 정리(`QueueCleanupScheduler`)는 어떻게 동작하나요?

**A.** `queue:token:{token}` String 키는 TTL(`ticketing.queue.token-ttl-seconds`, 기본 30분)이 설정되어 자동으로 사라지지만, ZSet(`queue:concert:{concertId}`)에는 TTL을 걸 수 없어 유령 멤버가 남습니다. `QueueCleanupScheduler`가 60초마다 분산 락을 잡고, `queueService.pruneExpiredTokens(concertId, batchSize)`를 호출합니다. 이 메서드는 `ZSCAN`으로 ZSet을 순회하면서 `EXISTS queue:token:{token}`이 false인 멤버를 모아 `ZREM`으로 일괄 제거합니다.

> **Q4-1. ZSet 자체에 TTL을 거는 대신 스케줄러로 정리하는 이유는요?**
> **A.** ZSet에 TTL을 걸면 공연 단위로 전체 대기열이 한꺼번에 삭제됩니다. 개별 멤버 단위로 만료시키는 것은 Redis ZSet에서 지원하지 않기 때문에, 개별 토큰은 String 키 TTL로 만료시키고, ZSet은 스케줄러가 주기적으로 정리하는 방식을 택했습니다.

---

### Q5. 부하 테스트에서 병목을 어떻게 파악하고, 어떤 순서로 튜닝하시겠습니까?

**A.** k6로 대기열 진입→폴링→홀드→결제 전체 플로우 시나리오를 돌리면서, Prometheus/Grafana에서 `ticketing_queue_waiting_count`, `ticketing_hold_created_total`, `ticketing_lock_acquire_failures_total`, `http_server_requests` p95 레이턴시를 관찰합니다. p95가 급격히 올라가는 VU 수가 knee point이고, 그 시점에서 어떤 메트릭이 먼저 튀는지로 병목을 판단합니다.

> **Q5-1. 병목이 확인되면 어떤 순서로 대응하나요?**
> **A.** 1단계로 설정 기반 튜닝(`queue.batch-size`, `processing-interval-ms`, `hold.ttl-seconds`, Redis `max-active`, HikariCP `maximum-pool-size`)을 합니다. 2단계로 읽기 부하 분산(콘서트/좌석 캐시 강화). 3단계로 애플리케이션 인스턴스 수평 확장(이미 Redis 세션 + 분산 락으로 준비됨). 4단계는 대기열을 별도 서비스로 분리하거나 Redis Cluster 도입입니다.
