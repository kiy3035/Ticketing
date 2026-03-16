# Redis / 데이터 모델

---

### Q1. 이 프로젝트에서 Redis를 어디에 어떻게 사용했는지 전체적으로 설명해 주세요.

**A.** Redis는 "실시간성 + 휘발성 + 대량 동시성"이 필요한 영역을 전담합니다. 구체적으로 대기열(`queue:concert:{id}` ZSet, `queue:token:{token}` String, `queue:allowed:{token}` String), 홀드(`hold:seat:{seatId}`, `hold:token:{token}`, `hold:expires` ZSet, `hold:user:{userId}` Set), 분산 락(`lock:seat:{seatId}`, `lock:batch:*`), 세션(`ticketing:sessions:*`), 활성 사용자 추적(`ActiveUserTracker` ZSet) 등에 사용했습니다. 메모리 정책은 `maxmemory 400mb`, `maxmemory-policy allkeys-lru`이고, 각 키에 TTL을 설정하고 스케줄러로 만료 데이터를 정리하는 구조입니다.

> **Q1-1. 왜 Redis 하나에 모든 기능을 넣었나요? 용도별로 분리하는 게 낫지 않나요?**
> **A.** 인프라 스펙이 t3a.medium 1대로 제한되어 있어, Redis 인스턴스를 여러 개 띄우면 각각에 메모리를 나눠야 해서 오히려 비효율적입니다. 대신 키 네이밍으로 `queue:*`, `hold:*`, `lock:*`, `ticketing:sessions:*` 등 prefix를 명확히 구분해서, Redis Insight로 모니터링할 때 어떤 키가 어떤 기능인지 바로 파악할 수 있게 했습니다. 향후 트래픽이 커지면 prefix 기반으로 별도 인스턴스나 클러스터 해시 슬롯을 분리할 수 있도록 준비된 상태입니다.

---

### Q2. 대기열 데이터를 ZSet + 별도 String 키로 나눈 이유는 무엇인가요?

**A.** 대기열 ZSet(`queue:concert:{concertId}`)은 토큰을 멤버, 진입 시각(밀리초)을 score로 저장해 순번 조회(`ZRANK`, O(log N))와 대기인원 조회(`ZCARD`, O(1))를 빠르게 처리합니다. 토큰 메타데이터(userId, concertId, enteredAt)는 별도 `queue:token:{token}` String에 JSON으로 저장해, 필요할 때만 읽도록 분리했습니다. 이렇게 하면 ZSet의 멤버 크기가 UUID(36바이트)로 일정하게 유지되어 메모리 효율이 좋고, 메타데이터 구조를 바꿔도 ZSet은 영향받지 않습니다.

> **Q2-1. `queue:allowed:{token}`은 왜 또 별도 키로 두셨나요?**
> **A.** 입장 허용 상태를 ZSet에 넣으면 순번/정렬과 상태가 섞여서 로직이 복잡해집니다. `QueueService.allowEntry()`에서 `queue:allowed:{token}`에 concertId와 허용 시각을 JSON으로 저장하고, 프론트에서 폴링 시 `QueueService.isAllowed(token)`으로 단순히 키 존재 여부만 확인합니다. TTL을 `tokenTtlSeconds`(기본 30분)와 동일하게 걸어서, 입장 허용 후 일정 시간 내에만 좌석 페이지에 접근할 수 있도록 제어합니다.

---

### Q3. 홀드(Hold) 관련 Redis 키 설계를 자세히 설명해 주세요.

**A.** 홀드는 네 가지 키로 구성했습니다. `hold:seat:{seatId}`(String)는 좌석→홀드 토큰 매핑으로, `HoldStore.CREATE_SCRIPT`에서 `EXISTS`로 중복 홀드를 차단하고 TTL=홀드 TTL(기본 10분)을 겁니다. `hold:token:{holdToken}`(String)은 토큰→홀드 정보 JSON으로, 결제 시 홀드 유효성 검증에 사용됩니다. `hold:expires`(ZSet)는 score=만료 시각(밀리초), member=payload JSON으로, `HoldCleanupScheduler`가 `ZRANGEBYSCORE hold:expires 0 now`로 만료 항목을 일괄 조회해 정리합니다. `hold:user:{userId}`(Set)는 사용자별 활성 홀드 토큰 목록으로, "내 예매 현황" 조회에 사용됩니다.

> **Q3-1. `hold:expires`에 score가 아니라 member에 JSON을 넣은 이유는요?**
> **A.** 만료 홀드를 정리할 때 `ZRANGEBYSCORE`로 가져온 결과에서 바로 `HoldInfo`를 역직렬화해 `releaseByPayload()`에 전달해야 합니다. member에 JSON payload를 넣으면 별도로 `hold:token:{token}`을 다시 조회하지 않아도 되어, cleanup 스케줄러에서 네트워크 왕복을 줄일 수 있습니다. 단점은 member 크기가 커져 ZSet 메모리가 늘어나지만, 동시 활성 홀드 수가 수백~수천 건 수준이라 실질적 영향은 미미합니다.

---

### Q4. Redis 메모리 정책(`maxmemory 400mb`, `allkeys-lru`)과 TTL 전략을 설명해 주세요.

**A.** Docker Compose에서 Redis에 `maxmemory 400mb`, `maxmemory-policy allkeys-lru`, `save ""`(RDB 비활성화)를 설정했습니다. 개별 키 TTL은 대기열 토큰 30분(`ticketing.queue.token-ttl-seconds`), 홀드 10분(`ticketing.hold.ttl-seconds`, 결제 중 20분으로 연장), 세션 30분, 캐시(콘서트 목록) 5분입니다. TTL이 없는 ZSet(`queue:concert:*`, `hold:expires`)은 `QueueCleanupScheduler.pruneExpiredTokens()`과 `HoldCleanupScheduler`가 각각 주기적으로 정리합니다.

> **Q4-1. `allkeys-lru`를 선택한 이유는요? `volatile-lru`가 더 안전하지 않나요?**
> **A.** `volatile-lru`는 TTL이 설정된 키만 eviction 대상이라, TTL 없는 ZSet(`hold:expires`, `queue:concert:*`)은 절대 제거되지 않습니다. 만약 스케줄러 정리가 밀려서 이 ZSet들이 메모리를 차지하면, TTL이 있는 정상 키가 먼저 밀려나는 역전 현상이 생길 수 있습니다. `allkeys-lru`를 쓰면 모든 키가 eviction 대상이 되어 극단적 상황에서도 Redis가 OOM으로 죽지 않습니다. 물론 중요한 키가 밀려날 수 있지만, 이 프로젝트에서 Redis 데이터는 모두 휘발성이고 DB가 source of truth이므로 허용 가능한 트레이드오프입니다.

---

### Q5. 키 네임스페이스 충돌이나 혼동을 어떻게 방지했나요?

**A.** 모든 키에 기능별 prefix를 적용했습니다. `QueueService`는 `QUEUE_CONCERT_KEY_PREFIX = "queue:concert:"`, `QUEUE_TOKEN_KEY_PREFIX = "queue:token:"`, `QUEUE_ALLOWED_KEY_PREFIX = "queue:allowed:"`를 상수로 정의하고, `HoldStore`는 `SEAT_KEY_PREFIX = "hold:seat:"`, `TOKEN_KEY_PREFIX = "hold:token:"`, `EXPIRY_ZSET_KEY = "hold:expires"`, `USER_HOLDS_PREFIX = "hold:user:"`를 사용합니다. 락은 `lock:seat:{seatId}`와 `lock:batch:{batchName}` 패턴이고, 세션은 Spring Session이 `ticketing:sessions:*`를 관리합니다.

> **Q5-1. 새 기능 추가 시 키가 겹치지 않으려면 어떤 규칙을 따라야 하나요?**
> **A.** `{도메인}:{자원}:{식별자}` 패턴을 따르도록 했습니다. 예를 들어 `queue:concert:1`, `hold:seat:42`, `lock:batch:queue-process` 같은 형태입니다. 새 기능을 추가할 때는 기존에 없는 도메인 prefix를 먼저 정하고, `docs/data.md`의 키 설계 표에 추가한 뒤 코드에 상수로 선언하는 순서를 따릅니다. 이렇게 하면 Redis Insight에서 prefix만으로 어떤 기능의 데이터인지 바로 구분할 수 있습니다.

---

### Q6. 결제 진행 중 홀드 TTL 연장은 어떻게 구현했나요?

**A.** `PaymentService.requestPayment()`에서 `holdStore.extendHoldTtl(holdToken, Duration.ofSeconds(extensionSeconds))`를 호출합니다. 기본값은 `ticketing.payment.hold-extension-ttl-seconds = 1200`(20분)입니다. `HoldStore.extendHoldTtl()`은 기존 payload를 읽어 새 만료 시각으로 `HoldInfo`를 재생성한 뒤, `hold:seat:{seatId}`와 `hold:token:{token}`의 TTL을 갱신하고, `hold:expires` ZSet에서 기존 payload를 `ZREM` 후 새 payload를 `ZADD`합니다.

> **Q6-1. 연장 로직이 Lua 스크립트가 아닌 개별 명령어로 되어 있는데, 원자성 문제는 없나요?**
> **A.** 엄밀히 말하면 중간에 장애가 나면 일부만 갱신될 수 있습니다. 다만 결제 요청 시점에는 이미 해당 사용자가 홀드를 소유한 상태이고, 다른 사용자가 같은 좌석에 접근할 수 없으므로 경합이 발생하지 않습니다. 그래도 더 안전하게 만들려면 연장도 Lua 스크립트로 감싸는 것이 좋고, 이 부분은 개선 포인트로 인식하고 있습니다.
