# Redis / 데이터 모델

---

### 🟢 Q1. 이 프로젝트에서 Redis를 어디에 사용했는지 전체적으로 설명해 주세요.

**A.** Redis 는 **실시간성 + 휘발성 + 대량 동시성** 영역을 전담합니다. 인증은 JWT 라 세션은 사용하지 않습니다.
- **대기열**: `queue:concert:{id}` ZSet, `queue:token:{token}` String, `queue:allowed:{token}` String
- **홀드**: `hold:seat:{seatId}`, `hold:token:{token}`, `hold:expires` ZSet, `hold:user:{userId}` Set
- **분산 락**: `lock:seat:{seatId}` (좌석), `lock:batch:queue-process|queue-cleanup|hold-cleanup|refund|kafka-outbox` (배치)
- **JWT Access 블랙리스트**: `jwt:bl:{jti}`
- **알림 List**: `notify:user:{userId}` (LPUSH + LTRIM 50건, 7일 TTL)
- **활성 사용자**: `active:users` ZSet
- **캐시**: 콘서트 목록 5분, 잔여석 2초
- **레이트리밋**: `ratelimit:{identifier}` ZSet (Sliding Window)
- **멱등성**: `idempotency:{key}` String

메모리 정책: `maxmemory 400mb`, `maxmemory-policy allkeys-lru`, RDB 비활성. 각 키에 TTL 또는 스케줄러 정리.

> **🟢 Q1-1. 왜 Redis 하나에 모든 기능을 넣었나요?**
> **A.** 인프라 스펙이 t3a.medium 1대(인프라서버)로 제한돼 Redis 인스턴스를 여러 개 띄우면 메모리를 나눠야 해서 비효율적입니다. 대신 **키 prefix로 영역을 분리** (`queue:*`, `hold:*`, `lock:*`, `jwt:bl:*` 등) 해서 Redis Insight 모니터링이 가능하고, 향후 prefix 기반으로 별도 인스턴스나 Cluster 해시 슬롯으로 분리할 수 있게 준비된 상태입니다.

---

### 🟡 Q2. 대기열 데이터를 ZSet + 별도 String 키로 나눈 이유는?

**A.** ZSet (`queue:concert:{concertId}`) 은 토큰을 멤버, 진입 시각(밀리초)을 score 로 저장 → 순번 조회 `ZRANK` O(log N), 대기인원 `ZCARD` O(1). 토큰 메타데이터(userId, concertId, enteredAt) 는 별도 `queue:token:{token}` String 에 JSON. 이렇게 하면:
- ZSet 멤버 크기가 UUID(36바이트)로 일정 → 메모리 효율
- 메타데이터 구조 변경이 ZSet 에 영향 없음
- 순번 정렬과 메타데이터 조회를 독립적으로 캐시 정책 적용 가능

> **🟡 Q2-1. `queue:allowed:{token}` 은 왜 또 별도 키?**
> **A.** 입장 허용 상태를 ZSet 에 넣으면 순번/정렬과 상태가 섞여서 로직이 복잡해집니다. `QueueService.allowEntry()` 가 별도 키에 `{concertId, allowedAt}` JSON 저장하고, 폴링 시 `isAllowed(token)` 가 단순 키 존재 확인. TTL 을 `tokenTtlSeconds` 와 동일하게 걸어 입장 허용 후 일정 시간 안에만 좌석 페이지 접근 가능.

---

### 🟡 Q3. 홀드 관련 Redis 키 설계를 자세히 설명해 주세요.

**A.** 4개 키:
- `hold:seat:{seatId}` (String): 좌석→홀드 토큰. `HoldStore.CREATE_SCRIPT` 의 `EXISTS` 가 중복 홀드 차단. TTL = `ticketing.hold.ttl-seconds` (기본 10분, 결제 단계 진입 시 20분으로 연장).
- `hold:token:{holdToken}` (String): 토큰→`HoldInfo` JSON. 결제 시 홀드 유효성 검증.
- `hold:expires` (ZSet): score=만료 시각(ms), member=payload JSON. `HoldCleanupScheduler` 가 `ZRANGEBYSCORE 0 now` 로 만료 항목 일괄 조회.
- `hold:user:{userId}` (Set): 사용자별 활성 홀드 토큰 인덱스. "내 홀드 목록" API 에서 O(전역 홀드 수) 스캔 회피.

> **🟡 Q3-1. `hold:expires` 에 score 가 아니라 member 에 JSON 을 넣은 이유는?**
> **A.** 만료 정리 시 `ZRANGEBYSCORE` 결과에서 바로 `HoldInfo` 로 역직렬화해 `releaseByPayload()` 에 전달할 수 있습니다. 별도로 `hold:token:{token}` 을 다시 조회하지 않아도 되어 cleanup 의 네트워크 왕복을 줄입니다. 단점은 member 크기 증가지만, 동시 활성 홀드가 수백~수천 건 수준이라 영향 미미.

> **🔴 Q3-2. `hold:user:{userId}` 의 정합성은?**
> **A.** Set 은 토큰 키와 별도로 유지되므로 토큰이 TTL 로 사라져도 Set 멤버는 남을 수 있습니다. `HoldStore.getHoldsByUser` 가 멤버마다 `hold:token:{token}` GET 해보고, 없으면 `SREM` 으로 자가 정리합니다. release/expire 경로에서도 Set 에서 제거. "조회 시 자가 청소(self-pruning)" 패턴.

---

### 🟡 Q4. Redis 메모리 정책(`maxmemory 400mb`, `allkeys-lru`)과 TTL 전략?

**A.** Docker Compose 에서 Redis 에 `maxmemory 400mb`, `maxmemory-policy allkeys-lru`, `save ""` (RDB 비활성). 개별 키 TTL:
- 대기열 토큰: 30분 (`ticketing.queue.token-ttl-seconds`)
- 홀드: 10분, 결제 진입 시 20분 연장
- JWT Access 블랙리스트: 토큰 남은 만료 시간
- 캐시(콘서트 목록): 5분
- 잔여석 캐시: 2초
- 알림 List: 7일

TTL 없는 ZSet (`queue:concert:*`, `hold:expires`) 은 `QueueCleanupScheduler.pruneExpiredTokens()` 와 `HoldCleanupScheduler` 가 주기적으로 정리.

> **🔴 Q4-1. `allkeys-lru` 를 선택한 이유는? `volatile-lru` 가 더 안전하지 않나요?**
> **A.** `volatile-lru` 는 TTL 있는 키만 eviction 대상이라, TTL 없는 ZSet (`hold:expires`, `queue:concert:*`) 은 절대 제거되지 않습니다. 만약 스케줄러 정리가 밀려서 이 ZSet 들이 메모리를 차지하면 TTL 있는 정상 키가 먼저 밀려나는 **역전 현상** 발생. `allkeys-lru` 는 모든 키가 후보라 OOM 으로 죽지 않습니다. Redis 는 모두 휘발성이고 DB 가 source of truth 라 허용 가능한 트레이드오프입니다.

---

### 🟡 Q5. 키 네임스페이스 충돌은 어떻게 방지하나요?

**A.** `{도메인}:{자원}:{식별자}` 패턴을 따릅니다. 모든 키 prefix 가 코드 상수로 정의됨:
- `QueueService`: `QUEUE_CONCERT_KEY_PREFIX`, `QUEUE_TOKEN_KEY_PREFIX`, `QUEUE_ALLOWED_KEY_PREFIX`
- `HoldStore`: `SEAT_KEY_PREFIX = "hold:seat:"`, `TOKEN_KEY_PREFIX = "hold:token:"`, `EXPIRY_ZSET_KEY = "hold:expires"`, `USER_HOLDS_PREFIX = "hold:user:"`
- 락: `lock:seat:{seatId}`, `lock:batch:{batchName}`
- JWT 블랙리스트: `jwt:bl:{jti}`
- 캐시: `concert:list:*`, `queue:status:available-seats:*`

새 기능 추가 시 (1) 새 도메인 prefix 정하기 → (2) `docs/data.md` 표에 추가 → (3) 코드에 상수 선언 순서.

---

### 🟡 Q6. 결제 진행 중 홀드 TTL 연장은 어떻게?

**A.** `PaymentService.requestPayment()` 에서 `holdStore.extendHoldTtl(holdToken, Duration.ofSeconds(1200))` 호출 (20분, `ticketing.payment.hold-extension-ttl-seconds`). `HoldStore.extendHoldTtl()`:
1. 기존 payload 읽어 새 만료 시각으로 `HoldInfo` 재생성
2. `hold:seat:{seatId}` 와 `hold:token:{token}` 의 TTL 갱신
3. `hold:expires` ZSet 에서 기존 payload `ZREM` 후 새 payload `ZADD`

> **🔴 Q6-1. 연장 로직이 Lua 가 아닌 개별 명령어인데 원자성 문제는?**
> **A.** 엄밀히 말하면 중간 장애 시 일부만 갱신될 수 있습니다. 다만 결제 요청 시점에는 이미 해당 사용자가 홀드를 소유 중이고 다른 사용자가 같은 좌석에 접근할 수 없어 경합이 거의 없습니다. 더 안전하게 만들려면 연장도 Lua 로 감싸는 게 맞고, **개선 포인트로 인지하고 있습니다.**

---

### 🔴 Q7. Redis 가 단일 인스턴스라 SPOF 인데, 어떻게 보완하나요?

**A.** 현재는 단일 인스턴스 + 다음 단계 인지를 솔직히 말합니다.
- **현재**: `RedisCircuitBreakerExecutor` 로 fast-fail + fallback. `ticketingDatastores` 헬스가 DOWN 으로 떨어지면 ALB 가 트래픽 빼게 됨.
- **다음 단계**: Redis Sentinel(자동 페일오버) 또는 Cluster(샤딩 + 복제) 도입. ElastiCache 같은 매니지드 서비스로 운영 부담 감소.
- **데이터 측면**: 모든 Redis 데이터는 휘발성으로 설계 — 영속이 필요한 건 DB. Redis 가 죽어도 DB 데이터는 무사.
