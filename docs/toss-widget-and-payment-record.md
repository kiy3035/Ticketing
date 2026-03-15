# 토스 주문서형 위젯 · 결제 기록 정리

토스 결제창형 → 주문서형(위젯) 전환, 카드 결제 기록·통계·UI 반영, k6·예매내역 UI 수정까지의 **흐름**만 정리한다. 세부 필드·DDL은 기존 문서 및 소스 주석 참고.

---

## 1. 토스 연동 방식 전환 (결제창형 → 주문서형)

- **결제창형**: `payment.requestPayment({ method: 'CARD', ... })` 로 토스가 **팝업/리다이렉트** 결제창 띄움.  
  → 실결제 키 사용 시 카카오뱅크 QR·신한 SOL페이 등 **실제 결제창**이 뜨는 이슈.
- **주문서형(위젯)**: 페이지 안에 **결제 수단 선택 + 이용약관** 위젯을 렌더하고, 결제 시 `widgets.requestPayment({ orderId, successUrl, failUrl })` 호출.  
  → 토스 표준 권장 방식이며, **결제위젯 연동 키**만 사용 가능.

**흐름**

1. 결제 페이지 로드 → 좌석 금액 로드.
2. 사용자가 **카드** 선택 → 위젯 영역 표시, `initTossWidgetIfNeeded()`:  
   - `/api/payments/toss-client-key` 로 클라이언트 키 조회.  
   - **test_gck_...** 만 허용 (test_ck_ 이면 "결제위젯 연동 키 필요" 에러).  
   - `TossPayments(clientKey).widgets({ customerKey: ANONYMOUS })` → `setAmount`, `renderPaymentMethods`, `renderAgreement`.
3. **결제하기** 클릭 → `POST /api/payments/request` (holdToken, paymentMethod: CARD) → 서버가 READY Payment + orderId 생성·반환.  
   → `widgets.requestPayment({ orderId, orderName, successUrl, failUrl })` 호출.
4. 토스 결제 완료 후 **successUrl** 리다이렉트 (paymentKey, orderId, amount + ourPaymentKey 쿼리).  
   → 같은 결제 페이지에서 `handleTossReturn()` 이 ourPaymentKey 로 `approve`(body: 토스 paymentKey/orderId/amount) → `complete` 호출.

**설정**

- `.env`: 주문서형은 **결제위젯 연동 키** 필수.  
  - 클라이언트: `test_gck_...` (문서용: `test_gck_docs_...`)  
  - 시크릿: `test_gsk_...` (문서용: `test_gsk_docs_...`)  
- `application.properties` 주석: 위젯용 gck/gsk 세트, API 개별 연동 키(ck/sk)는 위젯 미지원.

---

## 2. 카드(토스) 결제 기록 · 통계 · UI

- **DB**: 기존 `payment` 테이블의 `payment_method`(POINT/CARD) 그대로 사용. **별도 DDL 없음.**
- **목표**: 포인트처럼 “얼마가 어떻게 결제됐는지” 기록·표시. 관리자에는 수단별 누적, 예매내역에는 수단·금액 문구 구분.

**흐름**

1. **관리자 결제 목록**  
   - `AdminPaymentResponse`에 `paymentMethod` 추가.  
   - `getPayments`에서 `findByUsername(payment.getUserId())` 로 사용자명 조회, paymentMethod 포함 반환.  
   - 화면: 결제수단 컬럼(포인트/카드), 금액은 **포인트 → "N포인트"**, **카드 → "N원"**.
2. **관리자 통계**  
   - `sumAmountByStatusAndPaymentMethod(PaymentMethod.POINT/CARD)` 로 수단별 누적.  
   - 응답: `totalRevenuePoint`, `totalRevenueCard`.  
   - 화면: "포인트 매출 누적", "카드 결제 누적" 각각 표시.
3. **예매 내역**  
   - `PaymentRepository.findByReservationId` 로 예약별 결제 조회.  
   - `ReservationItemResponse`에 `paymentMethod` 추가, `listByUser`에서 채워서 반환.  
   - 화면: "결제수단 포인트/카드", 금액은 **포인트 → "N포인트 차감"**, **카드 → "N원 카드 결제"**.

---

## 3. k6 풀 플로우 · 예매내역 UI

- **k6 full-flow**: 카드는 위젯/리다이렉트로 자동화 불가하므로, **항상 포인트 결제**만 사용하도록 통일.  
  - `PAYMENT_METHOD` 제거, request → approve(본문 없음) → complete 만 수행.  
  - `load-tests/README.md` 실행 예·설명을 포인트 결제 기준으로 정리.
- **예매 내역 UI**:  
  - "좌석" 라벨이 찌그러지지 않도록 `.reservation-detail .label` 에 min-width/min-height, 패딩, 아이콘 크기 조정.  
  - 카드 결제 금액 문구: "N원 카드 결제"로 명시.

---

## 4. 관련 파일 (참고)

| 구분 | 파일 |
|------|------|
| 프론트 결제 | `static/payment.html`, `static/js/payment.js` |
| 백엔드 결제 | `PaymentController`, `PaymentService`, `TossPaymentsClient`, `PaymentRepository` |
| 관리자 | `AdminService`(getPayments, getPaymentStatistics), `AdminPaymentResponse`, `admin.html`, `admin.js` |
| 예매 내역 | `ReservationService.listByUser`, `ReservationItemResponse`, `reservations.js` |
| 설정 | `application.properties`(toss), `.env.example` |
| 부하 테스트 | `load-tests/full-flow.js`, `load-tests/README.md` |

상세 동작·파라미터는 각 소스 파일 주석 참고.
