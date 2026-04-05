# 01. 전체 워크플로우 (한 번에 따라가기)

사용자 시나리오 기준으로 **화면 → API → 서비스 → DB/Redis**가 어떻게 이어지는지 정리했다.

---

## 1. 사용자 예매 플로우 (정상 경로)

### 1) 로그인·공연 목록

- **화면**: `/login.html` → 로그인 후 `/app.html`
- **API**: `GET /api/auth/me`, `GET /api/concerts` (ConcertController → ConcertService, Redis 캐시)
- **데이터**: MySQL `concert` 조회 또는 캐시, 응답에 공연 목록

### 2) 대기열 필요 여부

- **화면**: 공연 클릭 시 `GET /api/queue/required?concertId=...` 호출
- **API**: QueueController.required() → QueueService.countWaiting()
- **데이터**: Redis `queue:concert:{concertId}` ZSet 크기, `activation-threshold`와 비교해 `required=true/false` 반환
- **동작**: `required=false`면 대기열 페이지 없이 바로 좌석 페이지로, `true`면 대기열 페이지로

### 3) 대기열 진입 (필요 시)

- **화면**: `/queue.html?concertId=...`
- **API**: `POST /api/queue/enter?concertId=...` (QueueController.enter → QueueService.enterQueue)
- **데이터**: Redis에 `queue:token:{token}`, `queue:concert:{concertId}` ZSet에 토큰 추가, 즉시 입장 허용 조건이면 `queue:allowed:{token}` 설정
- **응답**: token, rank, totalWaiting, immediatelyAllowed

### 4) 대기열 폴링·입장 허용

- **화면**: 2초마다 `GET /api/queue/status?token=...&concertId=...` 호출
- **API**: QueueController.status() → getRank, countWaiting, isAllowed, 가용 좌석 수(총 - 예매완료 - 홀드)
- **입장 허용**: QueueProcessingScheduler가 2초마다 상위 N명에게 `queue:allowed:{token}` 설정 → status 응답의 isAllowed=true 되면 화면에서 `/concert.html?concertId=...&queueToken=...`로 이동

### 5) 좌석 조회·선택

- **화면**: `/concert.html` — 좌석 그리드 표시
- **API**: `GET /api/concerts/{concertId}/seats` (SeatController → SeatService.listSeats)
- **데이터**: MySQL `seat` 조회 + Redis HoldStore.findHeldSeatIds()로 홀드된 좌석 파악 → 응답에 seatId, section, seatNo, price, status(AVAILABLE/HELD/RESERVED)

### 6) 홀드 생성

- **화면**: 좌석 클릭 → "결제하기" → HoldService 호출 후 `/payment.html?concertId=...&seatId=...&holdToken=...` 이동
- **API**: `POST /api/holds` body `{ concertId, seatId }` (HoldController.createHold → HoldService.createHold)
- **흐름**:
  1. Seat 조회, 공연 일치·과거 공연·취소된 공연 검사
  2. `lock:seat:{seatId}` 분산 락 획득 (RedisLockService)
  3. 좌석이 이미 RESERVED면 409
  4. HoldStore.createHold() — Redis Lua로 `hold:seat:{seatId}`, `hold:token:{holdToken}`, `hold:expires` ZSet에 저장, `hold:user:{userId}` Set에 토큰 추가
  5. Kafka HOLD_CREATED 발행
  6. 락 해제
- **응답**: holdToken, expiresAt

### 7) 결제 (포인트)

- **화면**: `/payment.html` — 결제 수단 선택 후 "결제 요청" → "승인" → "완료"
- **API 순서**:
  1. `POST /api/payments/request` body `{ holdToken, paymentMethod: "POINT" }`  
     → PaymentService.requestPayment: 홀드 검증, TTL 연장, READY 상태 Payment 저장
  2. `POST /api/payments/{paymentKey}/approve` (body 없음)  
     → approvePaymentWithOption: 포인트 차감, APPROVED
  3. `POST /api/payments/{paymentKey}/complete`  
     → completePayment: ReservationService.confirm() 호출 → 예약 생성, COMPLETED, reservationId 저장, Kafka 결제 완료 이벤트
- **예약 확정**: `confirm()` 한 트랜잭션 안에서 좌석 `RESERVED`·예약 저장·**`kafka_outbox` 에 `RESERVATION_CONFIRMED` 페이로드 INSERT** 까지 커밋된다. 커밋 후 **`ReservationConfirmedEventListener`(AFTER_COMMIT)** 는 **Redis 홀드 해제만** 한다. **`RESERVATION_CONFIRMED` Kafka 전송**은 **`KafkaOutboxPublishScheduler`** 가 outbox 행을 읽어 `send` 하고 성공 시 행을 **삭제**한다.

### 8) 예매 내역 조회

- **화면**: `/reservations.html` 또는 마이페이지
- **API**: `GET /api/reservations/me` (ReservationController → ReservationService.listByUser)
- **데이터**: MySQL reservation 조회 + Payment에서 결제 수단 보충

---

## 2. 판매자: 공연 등록·취소

- **공연 등록**: `/seller.html` → `POST /api/seller/concerts`, `POST /api/seller/concerts/{id}/seats`
- **공연 취소**: 공연 목록에서 "취소" 버튼 → `POST /api/seller/concerts/{concertId}/cancel` (SellerController → SellerService.cancelConcert)  
  → Concert.status = CANCELLED  
  → **환불은 스케줄러가 처리** (RefundForCancelledConcertScheduler, 기본 5분 주기)

---

## 3. 환불 배치 (공연 취소 후)

- **실행**: RefundForCancelledConcertScheduler (fixedDelay, 기본 300000ms = 5분)
- **동작**: Concert.status = CANCELLED인 공연의 COMPLETED 결제를 페이징 조회 → 각 건마다  
  1. cancelReservationForRefund(reservationId) — 예약 CANCELLED, 좌석 AVAILABLE  
  2. POINT면 refundPoints()  
  3. Payment CANCELED, canceledAt 저장

---

## 4. 한 줄 요약

```
로그인 → app(공연 목록) → queue required 확인
  → (필요 시) queue enter → 폴링 status → 입장 허용 시 concert 페이지
  → seats 조회 → holds POST(홀드) → payment 페이지
  → payments/request → approve → complete (예약·outbox 커밋 → 커밋 후 Redis 홀드 해제 → outbox 스케줄러가 Kafka)
  → reservations/me 로 예매 내역 확인
```

공연 취소는 seller에서 cancel API 호출 → DB만 CANCELLED → 환불은 배치가 주기적으로 수행.
