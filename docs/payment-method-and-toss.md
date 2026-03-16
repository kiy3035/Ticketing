# 결제 수단 (포인트 / 카드)

## 개요

| 결제 수단 | 설명 |
|-----------|------|
| **POINT** | 회원 보유 포인트에서 차감. 가입 시 초기 포인트 지급. |
| **CARD** | 토스페이먼츠 샌드박스 연동. 주문서형 위젯으로 결제창 렌더 후 승인. |

사용자는 결제 단계에서 포인트 또는 카드 중 하나를 선택한다.

## API 플로우

```
1. POST /api/payments/request  (holdToken, paymentMethod)  → READY
2. POST /api/payments/{key}/approve
     - POINT: body 없음, 서버에서 포인트 차감
     - CARD:  body에 paymentKey/orderId/amount, 서버에서 토스 승인 API 호출
3. POST /api/payments/{key}/complete  → 예약 확정 + COMPLETED
```

카드 결제 시 프론트에서 `GET /api/payments/toss-client-key`로 클라이언트 키를 받아 토스 위젯을 초기화한다.

## 토스페이먼츠 연동

- **클라이언트**: 토스 주문서형 위젯 (`widgets.requestPayment`)
- **서버**: `TossPaymentsClient`가 `POST /v1/payments/confirm` 호출 (Secret Key 인증)
- **환경 변수**: `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY` (`.env`)
- 샌드박스 키(`test_ck_`, `test_sk_`)를 사용하면 실제 결제 미발생

## 취소/환불

- **포인트 결제 취소**: 포인트 환불 처리
- **카드 결제 취소**: 포인트 환불만 수행 (토스 취소 API 미연동 — 샌드박스 범위)
- **취소 공연 배치 환불**: `RefundForCancelledConcertScheduler`가 POINT 결제만 환불 처리

## DB 스키마 추가 컬럼

`payment` 테이블: `payment_method` (POINT/CARD), `order_id` (토스 주문 ID), `toss_payment_key` (토스 결제 키)
