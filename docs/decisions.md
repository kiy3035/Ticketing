# 기술 결정 요약 (ADR 통합)

면접에서 “왜 이렇게 했는가”를 짧게 말할 때 쓰는 **한 장 요약**이다. 상세 논증·대안 표는 필요 시 코드·테스트·부하 결과로 뒷받침한다.

<a id="adr-redis-lock"></a>

## 1. Redis SETNX 분산 락 (좌석 선점)

- **문제**: 앱 N대에서 동일 좌석 홀드/예약 경쟁 시 정확히 한 명만 성공해야 함.
- **결정**: `SETNX` + TTL + **Lua로 토큰 일치 시에만 DEL** (다른 소유자 락 오삭제 방지).
- **대안**: DB 비관적 락만 쓰기(느림), Redisson(의존성·복잡도), Redlock(단일 Redis면 불필요).
- **전제**: 현재는 Redis 단일 인스턴스. Sentinel/Cluster 전환 시 라이브러리·전략 재검토.

<a id="adr-kafka"></a>

## 2. Kafka 이벤트 드리븐

- **문제**: 결제·알림은 외부 I/O가 길어 결제 API 응답에 넣기 부적절.
- **결정**: 토픽으로 비동기 처리, 컨슈머에서 알림·SSE 연동. 실패 시 **재시도 + DLT(`*.DLT`)**.
- **프로듀서**: `acks=all`, `idempotence`, `retries` (일시 장애 완화). `RESERVATION_CONFIRMED` 는 **transactional outbox** 로 DB 커밋과 정합 ([sequence-diagrams §5](sequence-diagrams.md#consistency-failure-scenarios)).

<a id="adr-pessimistic"></a>

## 3. DB 비관적 락 (결제·포인트)

- **문제**: 금전·결제 상태 동시 갱신 시 충돌·이중 차감 방지.
- **결정**: `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) 로 Payment / Users / Reservation 등 핵심 갱신 구간 보호.
- **좌석 선점**은 Redis 락·Lua 홀드가 담당(§1과 역할 분리).

<a id="adr-idempotency"></a>

## 4. 결제 API 멱등성 키

- **문제**: 네트워크 재시도로 동일 결제 API가 두 번 오면 이중 처리 위험.
- **결정**: `Idempotency-Key` 헤더 + Redis에 처리 결과(또는 진행 중 마커) TTL 저장, AOP(`@Idempotent`) 적용.

<a id="adr-virtual-threads"></a>

## 5. Java 21 Virtual Thread (선택 적용)

- **문제**: I/O 대기 비중이 큰 API에서 플랫폼 스레드 풀 상한·메모리 비효율.
- **결정**: Tomcat 요청 스레드 가상 스레드화, 일부 배치·Kafka 리스너에서 I/O 병렬 시 가상 스레드 활용.
- **제외**: 스케줄러 트리거 스레드, Netty/Kafka Producer 내부, CPU 위주 작업 — 이득 없거나 제어 불가 영역.
