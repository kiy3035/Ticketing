# 기술 결정 요약 (ADR)

## ADR-1. Redis SETNX 분산 락 (Redisson 미사용)

- **문제**: 앱 N대에서 동일 좌석 홀드/예약 경쟁 시 정확히 한 명만 성공해야 함
- **결정**: `SETNX` + TTL + **Lua 토큰 일치 시에만 DEL** (다른 소유자 락 오삭제 방지)
- **Redisson 미사용 이유**: 단일 Redis 인스턴스에서 Redlock은 과도한 복잡도. UUID 토큰 + Lua 해제로 충분
- **전제**: Redis 단일 인스턴스. Sentinel/Cluster 전환 시 재검토 필요

## ADR-2. Kafka 이벤트 드리븐

- **문제**: 이메일/SMS 전송(외부 I/O)이 결제 API 응답 시간을 늘림
- **결정**: 결제 완료 후 Kafka 토픽으로 비동기 처리. `acks=all`, `idempotence=true`, `retries=3`
- **`RESERVATION_CONFIRMED`**: Transactional Outbox로 DB 커밋과 이벤트 발행을 정합성 있게 처리

## ADR-3. DB 비관적 락 (결제·포인트)

- **문제**: 금전 이중 차감 방지
- **결정**: `SELECT ... FOR UPDATE`로 Payment / Users 핵심 갱신 구간 보호
- **역할 분리**: 좌석 선점은 Redis 락 담당, 결제 정합성은 DB 락 담당

## ADR-4. 멱등성 키 (AOP)

- **문제**: 네트워크 재전송으로 결제 API 중복 실행 시 이중 결제
- **결정**: `Idempotency-Key` 헤더 + Redis TTL + `@Idempotent` AOP — 비즈니스 로직과 완전 분리

## ADR-5. Java 21 Virtual Thread

- **문제**: I/O 대기 비중이 큰 API에서 Platform Thread 풀 상한(200개) 도달
- **결정**: Tomcat 요청 스레드 Virtual Thread화. Kafka 리스너·일부 배치도 VT 적용
- **실측**: JVM live threads **~225개 → ~30개** (동일 VU 800 부하 기준)
- **제외**: 스케줄러 트리거, Netty/Kafka Producer 내부 (제어 불가 영역)
