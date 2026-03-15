# 04. 결제·환불 (상세)

결제 흐름(request → approve → complete)과, **공연 취소 시 환불 배치**가 어떤 순서로 동작하는지 정리했다.

---

## 1. 결제 흐름 (PaymentService)

### requestPayment (POST /api/payments/request)

- holdToken 검증(소유자, 존재), **홀드 TTL 연장** (결제 진행용, 설정값 예: 20분)
- 동일 holdToken으로 이미 Payment 있으면 그걸 반환 (재요청 안전)
- Seat 조회, **공연 CANCELLED 검사**
- Payment 생성: READY, holdToken, userId, concertId, seatId, amount, paymentMethod(POINT/CARD)
- CARD면 orderId 설정 (토스 위젯용)
- save 후 반환

### approvePayment / approvePaymentWithOption (POST /api/payments/{paymentKey}/approve)

- Payment PESSIMISTIC_WRITE로 조회, 소유자 검증
- 이미 APPROVED/COMPLETED면 그대로 반환, CANCELED면 409
- **CARD**: body(paymentKey, orderId, amount) 검증, 토스 confirm API 호출, tossPaymentKey 저장, APPROVED
- **POINT**: Users 포인트 PESSIMISTIC_WRITE로 조회, 잔액 검사 후 차감, APPROVED

### completePayment (POST /api/payments/{paymentKey}/complete)

- Payment PESSIMISTIC_WRITE, 소유자 검증
- APPROVED가 아니면 409
- **ReservationService.confirm(holdToken)** 호출 → 예약 생성, DB 커밋 후 리스너가 홀드 해제·RESERVATION_CONFIRMED 발행
- Payment COMPLETED, completedAt, reservationId 저장
- PaymentCompleteEventPublisher로 Kafka 발행 (이메일/SMS 등)

**정리**: 예약 확정은 **complete 단계에서만** 일어나고, 별도 "예약 확정 API"는 없다.

---

## 2. 결제 취소 (사용자 API)

- **API**: POST /api/payments/{paymentKey}/cancel
- **동작**: COMPLETED면 409(이미 완료된 결제는 취소 불가). APPROVED + POINT면 포인트 환불 후 CANCELED, canceledAt 저장. CARD면 그냥 CANCELED.

---

## 3. 공연 취소 시 환불 배치 (RefundForCancelledConcertScheduler)

**실행 주기**: fixedDelay, 기본 300000ms(5분). 테스트 시 `ticketing.refund.interval-ms=10000` 등으로 줄일 수 있다.

**한 번 돌 때**:
1. Concert.status = CANCELLED 인 공연 목록 조회
2. 공연별로 Payment.status = COMPLETED 인 건 페이징 조회 (batchSize)
3. 각 Payment에 대해 **PaymentService.refundCompletedPaymentForCancelledConcert(paymentId)** 호출

**refundCompletedPaymentForCancelledConcert 안에서 순서** (실패 시 불일치 방지):
1. **예약 취소·좌석 해제**: cancelReservationForRefund(reservationId) — Reservation findWithLockById, CANCELLED, Seat AVAILABLE
2. **포인트 환불**: POINT 결제만 refundPoints(); 실패 시 false 반환해 다음 배치에서 재시도
3. **결제 상태**: CANCELED, canceledAt 저장

CARD 결제는 포인트를 쓰지 않았으므로 환불 로직 없이 결제만 CANCELED 처리한다.

---

## 4. 테스트 시 참고

- **공연 취소 + 환불** 테스트: 판매자로 공연 취소 → 최대 1주기(기본 5분) 기다리거나, `ticketing.refund.interval-ms=10000`으로 줄인 뒤 재기동해서 10초 정도 기다리면 배치가 한 번 돌아가고, payment.canceled_at·reservation CANCELLED·seat AVAILABLE·users.point 복구 확인 가능.
