# 디바운스 & 쓰로틀

프로젝트에서 **디바운스**와 **쓰로틀(연타 방지)** 이 적용된 위치와 목적을 정리한 문서입니다.

---

## 요약

| 구분 | 적용 위치 | 방식 | 목적 |
|------|-----------|------|------|
| **디바운스** | `app.js` 검색 입력 | 300ms 지연 후 1회 호출 | 입력 중 API 호출 횟수 감소 |
| **쓰로틀(비활성화)** | `concert.js` 예매하기 버튼 | 클릭 시 버튼 비활성화, 실패 시만 재활성화 | 홀드 API 중복 요청 방지 |
| **쓰로틀(비활성화)** | `payment.js` 결제하기 버튼 | 클릭 시 버튼 비활성화, 실패 시만 재활성화 | 결제 요청 중복 방지 |

---

## 1. 디바운스 (Debounce)

### 적용: 콘서트 목록 검색 (`app.js`)

- **대상**: `#searchInput` `input` 이벤트
- **동작**: 사용자가 입력을 멈춘 뒤 **300ms** 후에 한 번만 `loadConcerts()` 호출
- **이유**: 키 입력마다 API를 호출하면 요청 수가 불필요하게 늘어나므로, 입력이 잠깐 멈출 때만 검색 실행

```javascript
// src/main/resources/static/js/app.js
searchInput.addEventListener('input', () => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(loadConcerts, 300);
});
```

---

## 2. 쓰로틀 / 연타 방지 (Throttle)

### 2-1. 예매하기 버튼 (`concert.js`)

- **대상**: `#reserveBtn` 클릭 → `startPayment()` → `holdSeat()` (POST /api/holds)
- **동작**:
  - 클릭 직후 `reserveBtn.disabled = true`
  - 홀드 성공 시 결제 페이지로 이동(버튼 상태 유지)
  - 홀드 실패 시 `reserveBtn.disabled = false` 로 재활성화하여 재시도 가능
- **이유**: 연타 시 동일 좌석에 대해 홀드 요청이 여러 번 나가는 것을 막고, 서버 부하 및 예기치 않은 상태를 방지

### 2-2. 결제하기 버튼 (`payment.js`)

- **대상**: `#payBtn` 클릭 → `submitPayment()` → POST /api/payments/request 등
- **동작**:
  - 클릭 직후 `payBtn.disabled = true`
  - 결제 성공(카드 리다이렉트 또는 포인트 완료) 시에는 그대로 비활성 유지
  - 예외 발생 시 `payBtn.disabled = false` 로 재활성화하여 재시도 가능
- **이유**: 결제 요청/승인 API 중복 호출 방지 및 이중 결제 가능성 감소

---

## 적용하지 않은 곳 (참고)

| 위치 | 이유 |
|------|------|
| 관리자 검색 (`admin.js`) | Enter/버튼 클릭 시에만 검색. 입력 중 API 호출 없음 |
| 회원가입 휴대폰 입력 (`signup.js`) | 포맷팅만 수행, API 호출 없음 |
| 좌석 그리드 클릭 (`concert.js`) | 좌석 선택만 변경, 클릭 시 홀드/결제 요청으로 이어지지 않음 |

---

## 관련 파일

- `src/main/resources/static/js/app.js` — 검색 디바운스
- `src/main/resources/static/js/concert.js` — 예매하기 버튼 비활성화
- `src/main/resources/static/js/payment.js` — 결제하기 버튼 비활성화
