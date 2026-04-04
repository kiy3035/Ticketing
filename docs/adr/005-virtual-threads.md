# ADR-005: Java 21 Virtual Thread 선택적 적용

## 상태
승인됨 (Accepted)

## 컨텍스트
- 콘서트 예매 시스템은 I/O 대기가 전체 요청 시간의 대부분을 차지한다 (Redis 락 → DB 트랜잭션 → Kafka 발행)
- 기존 Tomcat Platform Thread 풀(기본 200개)은 동시 요청 상한이 명확하다
- t3.small(vCPU 2, 2GB)에서 OS 스레드 200개는 스택 메모리만 ~200MB를 차지한다
- Java 21(LTS)이 Virtual Thread를 정식 제공하므로, I/O-bound 워크로드에 적합하다

## 결정
**Virtual Thread를 I/O 대기가 비싼 곳에만 선택적으로 적용**한다. CPU-bound이거나 라이브러리 내부 스레드는 건드리지 않는다.

### 적용한 곳 (I/O 대기가 비싼 곳)

| 위치 | 변경 | 이유 |
|------|------|------|
| Tomcat 요청 처리 | `spring.threads.virtual.enabled=true` | 모든 API가 Redis→DB→Kafka I/O 대기. 200개 한계 제거 |
| HoldCleanup 배치 | `Executors.newVirtualThreadPerTaskExecutor()` | 건당 Redis release + Kafka publish. 순차→병렬 |
| Refund 배치 | `Executors.newVirtualThreadPerTaskExecutor()` | 건당 DB 트랜잭션. 환불 건은 서로 독립적 |
| Kafka Consumer 리스너 | `SimpleAsyncTaskExecutor(virtualThreads=true)` | 이벤트 처리 시 DB 조회 + 이메일/SMS 발송 |

### 적용하지 않은 곳 (의도적 제외)

| 위치 | 이유 |
|------|------|
| @Scheduled 트리거 스레드 | 스케줄러 발동 자체는 가벼움. 내부 작업만 Virtual Thread로 위임 |
| QueueProcessingScheduler | 2초 주기, 건당 Redis 1ms 미만. 병렬화 이득 < 복잡도 |
| Lettuce(Redis) Netty I/O | Netty 이벤트 루프가 자체 관리. 제어 대상 아님 |
| Kafka Producer 내부 I/O | Kafka 클라이언트 자체 sender 스레드. 직접 제어 불가 |
| Prometheus 메트릭 수집 | CPU 연산 위주. Virtual Thread 이득 없음 |

## 대안 검토

| 방식 | 장점 | 단점 | 판정 |
|------|------|------|------|
| Platform Thread 200개 유지 | 안정적, 검증됨 | 동시성 상한 고정, 메모리 비효율 | ❌ |
| WebFlux(리액티브) 전환 | Non-blocking, 높은 처리량 | 전면 재작성, 러닝커브, JPA 미호환 | ❌ |
| **Virtual Thread 선택적 적용** | 기존 코드 그대로, I/O 대기만 효율화 | synchronized pinning 주의 | ✅ 채택 |
| Virtual Thread 전면 적용 | 간단 | 이득 없는 곳에도 오버헤드 | ⚠️ 과도 |

## Virtual Thread가 이 프로젝트에 적합한 이유

### 1. 요청 1건의 I/O 대기 비중
```
홀드 API 1건 기준:
  Redis 분산 락 획득/해제   ~2ms (I/O 대기)
  Redis Lua 홀드 생성       ~1ms (I/O 대기)
  Kafka 이벤트 발행         ~5ms (I/O 대기)
  실제 CPU 연산             ~0.1ms
  ──────────────────────────
  I/O 대기: 99%
```
→ Platform Thread가 99%의 시간을 "아무것도 안 하면서" 점유한다.
→ Virtual Thread는 I/O 대기 시 carrier thread를 반납하므로 2개 vCPU로 수천 동시 요청 처리 가능.

### 2. 배치 스케줄러 병렬화
```
기존 (순차):  만료 200건 × 8ms/건 = 1.6초
변경 (병렬):  200개 Virtual Thread → ~50ms (커넥션 풀이 자연스럽게 조절)
```

### 3. 인프라 효율
- t3.small(vCPU 2, 2GB)에서 OS 스레드 200개 → 스택 메모리 ~200MB
- Virtual Thread 스택은 수KB, 필요 시 자동 확장 → 수천 개 생성해도 수MB

## Pinning 주의 사항
- `synchronized` 블록 내에서 I/O를 수행하면 Virtual Thread가 carrier thread에 고정(pin)된다
- 현재 프로젝트에서는 `synchronized`를 직접 사용하지 않음
- MySQL Connector/J 9.x는 Virtual Thread 호환 완료 (내부 `synchronized` 제거)
- Lettuce(Redis)는 Netty 기반이므로 pinning 이슈 없음

## 결과
- Tomcat 동시 요청 상한: 200 → 사실상 무제한 (커넥션 풀·DB가 실제 병목)
- 배치 처리 시간: 순차 대비 ~30배 단축 (만료 홀드 정리 기준)
- 코드 변경 최소: 기존 동기 코드를 리액티브로 재작성할 필요 없음
- 면접 포인트: "어디에 썼는가"보다 **"어디에 안 썼는가, 왜"**가 차별점
