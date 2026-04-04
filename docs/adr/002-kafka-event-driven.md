# ADR-002: Kafka 이벤트 드리븐 아키텍처 도입

## 상태
승인됨 (Accepted)

## 컨텍스트
- 결제 완료 후 이메일/SMS 알림 전송이 필요하다
- 알림 전송은 외부 API(SMTP, Solapi)를 호출하므로 지연이 크고 실패할 수 있다
- 결제 API 응답 시간에 알림 전송 시간이 포함되면 안 된다

## 결정
**Kafka를 메시지 브로커로 도입**해 결제 완료 이벤트를 비동기로 처리한다.

### 토픽 구조

| 토픽 | 프로듀서 | 컨슈머 | 용도 |
|------|----------|--------|------|
| `ticketing.seat-hold-events` | HoldService, Scheduler | SeatHoldEventConsumer | 홀드 생성/만료/취소/예약확정 알림 |
| `ticketing.payment-complete` | PaymentService | PaymentCompleteConsumer | 결제 완료 → 이메일/SMS |
| `*.DLT` | DLQ ErrorHandler | (수동 모니터링) | 처리 실패 메시지 보관 |

### 에러 처리 전략
- **재시도**: 3회, 1초 간격 (FixedBackOff)
- **DLQ**: 재시도 실패 시 Dead Letter Topic으로 전송
- **전달 보장**: `acks=all`, `enable.idempotence=true`로 At-Least-Once 보장

## 대안 검토

| 방식 | 장점 | 단점 | 판정 |
|------|------|------|------|
| 동기 호출 | 구현 간단 | 응답 지연, 실패 시 결제 실패 | ❌ |
| @Async | JVM 내부, 간단 | 서버 재시작 시 유실, 재시도 불편 | ⚠️ |
| **Kafka** | 내구성, 재시도, DLQ, 확장성 | 인프라 추가 | ✅ 채택 |
| RabbitMQ | 라우팅 유연 | Kafka 대비 처리량 낮음 | ❌ |

## 결과
- 결제 API 응답시간에서 알림 전송 시간 제거 (p95 개선)
- 알림 실패가 결제 프로세스를 방해하지 않음
- DLQ로 실패 메시지 추적/재처리 가능
