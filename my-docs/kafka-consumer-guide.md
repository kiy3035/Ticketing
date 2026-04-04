# Kafka 컨슈머 설정 상세

## 토픽 구조

```
ticketing.seat-hold-events     → SeatHoldEventConsumer     (그룹: ticketing-notification)
ticketing.payment-complete     → PaymentCompleteConsumer   (그룹: ticketing-payment-notification)
ticketing.seat-hold-events.DLT → (수동 모니터링)           Dead Letter Topic
ticketing.payment-complete.DLT → (수동 모니터링)           Dead Letter Topic
```

## 직렬화/역직렬화

**기존 문제 (수정 전):**
- SeatHold Producer: `JsonSerializer<SeatHoldEvent>` (JSON 출력)
- SeatHold Consumer: `StringDeserializer` → 수동 `ObjectMapper.readValue()` 😱
- PaymentComplete: `JsonDeserializer<PaymentCompleteEvent>` (정상)

**수정 후:**
- 모든 Producer/Consumer가 `JsonSerializer`/`JsonDeserializer`를 일관되게 사용
- ObjectMapper를 Spring 빈으로 주입받아 JavaTimeModule 등 설정 통일

## DLQ (Dead Letter Queue) 동작

```java
// KafkaConfig.java
private CommonErrorHandler createErrorHandler(KafkaTemplate<String, Object> dltKafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate);
    // 3회 재시도, 1초 간격
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    return errorHandler;
}
```

**흐름:**
1. Consumer에서 예외 발생
2. 1초 후 재시도 (1/3)
3. 1초 후 재시도 (2/3)
4. 1초 후 재시도 (3/3) — 여전히 실패
5. `DeadLetterPublishingRecoverer`가 `ticketing.seat-hold-events.DLT` 토픽으로 전송
6. DLT에 쌓인 메시지는 운영팀이 모니터링해서 원인 파악 후 수동 재처리

## 메시지 전달 보장 수준

```properties
# application.properties
spring.kafka.producer.acks=all                        # 모든 ISR 복제본 확인
spring.kafka.producer.retries=3                       # 전송 실패 시 3회 재시도
spring.kafka.producer.properties.enable.idempotence=true  # 중복 전송 방지
spring.kafka.consumer.auto-offset-reset=latest        # 새 그룹 시 최신부터
```

**현재 보장 수준: At-Least-Once**
- Producer: `acks=all` + `enable.idempotence=true` → Exactly-Once에 가까움
- Consumer: 자동 커밋(기본) → 처리 중 실패 시 메시지 재수신 가능 → 중복 처리 가능
- 해결: Consumer에서 멱등성 처리 (같은 이벤트 2번 와도 OK)

## 새 토픽 추가 시 체크리스트

1. `KafkaConfig`에 Producer/Consumer Factory 추가
2. `ListenerContainerFactory`에 DLQ ErrorHandler 연결
3. Producer 서비스 클래스 생성
4. Consumer 리스너 클래스 생성 (`@KafkaListener`)
5. 토픽 상수를 `TicketingProperties`에 추가
