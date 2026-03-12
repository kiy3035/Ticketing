# 결제 수단(포인트/카드) 및 토스페이먼츠 연동

이 문서는 **결제 수단 선택(포인트 / 카드)** 및 **토스페이먼츠 샌드박스(모의결제)** 연동에서 변경·추가된 사항을 정리한 것이다.  
각 변경 파일의 **상세 동작과 의도**는 소스 코드 주석을 참고하면 된다.

---

## 1. 개요

- **포인트 결제**: 기존과 동일. 회원 보유 포인트(가입 시 지급 포함)에서 차감.
- **카드 결제**: 토스페이먼츠 결제창을 띄우고, 샌드박스 환경에서 모의결제 진행. **실제 출금/입금 없음.**

사용자는 결제 단계에서 "포인트 결제" / "카드 결제" 중 하나를 선택한다.

---

## 2. 변경·추가된 파일 목록

### 2.1 백엔드 (Java)

| 파일 | 변경 내용 |
|------|------------|
| `payment/domain/PaymentMethod.java` | **신규.** 결제 수단 enum: `POINT`, `CARD`. |
| `payment/domain/Payment.java` | `paymentMethod`, `orderId`, `tossPaymentKey` 필드 추가. |
| `payment/dto/PaymentRequest.java` | `paymentMethod` (기본 `POINT`) 추가. |
| `payment/dto/PaymentResponse.java` | `paymentMethod`, `orderId` 추가 (카드 시 위젯용). |
| `payment/dto/CardApproveRequest.java` | **신규.** 카드 승인 시 토스 리다이렉트로 받은 `paymentKey`, `orderId`, `amount`. |
| `payment/client/TossPaymentsClient.java` | **신규.** 토스 결제 승인 API `POST /v1/payments/confirm` 호출. |
| `config/PaymentConfig.java` | **신규.** `RestTemplate` 빈 등록 (토스 API 호출용). |
| `config/TicketingProperties.java` | `Toss` 내부 클래스 추가: `clientKey`, `secretKey`, `securityKey`. |
| `payment/service/PaymentService.java` | 요청 시 `paymentMethod` 반영, 승인 시 POINT(포인트 차감) / CARD(토스 승인) 분기, 취소·환불 시 POINT만 포인트 환불. |
| `payment/controller/PaymentController.java` | `POST /request`에 `paymentMethod`, `POST /approve`에 body(카드 승인 파라미터) 선택, `GET /toss-client-key` 추가. |
| `application.properties` | `ticketing.toss.client-key`, `ticketing.toss.secret-key`, `ticketing.toss.security-key` (환경 변수 주입). |

### 2.2 프론트엔드

| 파일 | 변경 내용 |
|------|------------|
| `static/payment.html` | 결제 수단 라디오(포인트/카드), 토스 스크립트 로드. |
| `static/js/payment.js` | 결제 수단별 분기: 포인트(기존 플로우), 카드(토스 결제창 → 리다이렉트 후 승인·완료). |

### 2.3 설정·문서

| 파일 | 변경 내용 |
|------|------------|
| `.env`, `.env.example` | `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`, `TOSS_SECURITY_KEY` 변수. |
| `load-tests/full-flow.js` | `PAYMENT_METHOD` 환경 변수 추가. POINT 시 결제 요청→승인→완료, CARD 시 직접 예약 확정으로 대체. |
| `load-tests/README.md` | 결제 수단 구분 및 실행 예 보강. |

---

## 3. API 동작 요약

- **POST /api/payments/request**  
  - Body: `holdToken`, `paymentMethod`(선택, 기본 `POINT`).  
  - 카드일 때 응답에 `orderId` 포함 (토스 결제창용).

- **POST /api/payments/{paymentKey}/approve**  
  - **포인트**: body 없음. 서버에서 포인트 차감 후 `APPROVED`.  
  - **카드**: body에 `paymentKey`, `orderId`, `amount` (토스 리다이렉트 쿼리와 동일). 서버에서 토스 승인 API 호출 후 `APPROVED`.

- **GET /api/payments/toss-client-key**  
  - 응답: `{ "clientKey": "..." }`. 프론트에서 토스 결제창 초기화용. 시크릿 키는 노출하지 않음.

---

## 4. k6 부하 테스트 결제 수단 구분

| 환경 변수 | 동작 |
|-----------|------|
| `PAYMENT_METHOD=POINT` (기본) | 홀드 → 결제 요청(POINT) → 승인(본문 없음) → 완료. 실제 포인트 차감. |
| `PAYMENT_METHOD=CARD` | 카드 승인은 브라우저(토스 리다이렉트) 필요하므로, k6에서는 **직접 예약 확정**(POST /api/reservations)으로 대체. 부하 검증용. |

실행 예:

```bash
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 -e TEST_USER=u -e TEST_PASS=p -e PAYMENT_METHOD=POINT load-tests/full-flow.js
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 -e TEST_USER=u -e TEST_PASS=p -e PAYMENT_METHOD=CARD load-tests/full-flow.js
```

---

## 5. 토스페이먼츠 키 설정

- `.env`에 다음을 설정한다.  
  - `TOSS_CLIENT_KEY`: 클라이언트 키 (프론트/결제창).  
  - `TOSS_SECRET_KEY`: 시크릿 키 (백엔드 승인 API).  
  - `TOSS_SECURITY_KEY`: (선택) 보안키.  
- 샌드박스는 `test_ck_`, `test_sk_` 로 시작하는 키를 사용하면 된다. 실제 결제는 발생하지 않는다.

---

## 6. DB 스키마

- `payment` 테이블에 컬럼 추가 (JPA `ddl-auto=update` 시 자동 반영):  
  - `payment_method` (enum: POINT, CARD)  
  - `order_id` (카드 결제 시 토스 주문 ID)  
  - `toss_payment_key` (카드 결제 승인 후 토스에서 받은 결제 키)

이전 결제 건은 `payment_method`가 없을 수 있음. 기본값 또는 마이그레이션 정책은 프로젝트 규칙에 따른다.
