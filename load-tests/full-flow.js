/**
 * k6 풀 플로우 부하 테스트
 *
 * 시나리오: 대기열 필요 시 진입 → 입장 허용 대기 → 좌석 조회 → 홀드 생성 → 결제(포인트/카드 구분) → 예약 확정
 * - PAYMENT_METHOD=POINT: 결제 요청 → 포인트 승인 → 결제 완료 (실제 포인트 차감)
 * - PAYMENT_METHOD=CARD: 결제 요청 후 카드 승인은 토스 리다이렉트가 필요하므로 k6에서는 불가.
 *   → CARD 선택 시 현재는 직접 예약 확정(POST /api/reservations)으로 대체하여 부하만 검증 (실제 결제 없음)
 * /api/holds, /api/payments, /api/reservations 는 인증 필요. TEST_USER, TEST_PASS 로 로그인 후 진행.
 *
 * 실행 예:
 *   k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=16 -e TEST_USER=아이디 -e TEST_PASS=비번 load-tests/full-flow.js
 *   k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=16 -e TEST_USER=아이디 -e TEST_PASS=비번 -e PAYMENT_METHOD=POINT load-tests/full-flow.js
 *   k6 run -e BASE_URL=... -e PAYMENT_METHOD=CARD load-tests/full-flow.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

// 부하 단계: 10명(30초) → 30명(1분) → 50명(1분) → 0명(30초) = 최대 동시 50 VU
export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 30 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<5000'],  // 95% 응답 시간 5초 미만
    http_req_failed: ['rate<0.2'],      // 실패율 20% 미만
  },
};

// 환경 변수 (미지정 시 기본값)
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CONCERT_ID = __ENV.CONCERT_ID || '1';
const TEST_USER = __ENV.TEST_USER || '';
const TEST_PASS = __ENV.TEST_PASS || '';
/** 결제 수단: POINT(포인트 결제, 기본) | CARD(카드 선택 시 k6에서는 토스 리다이렉트 불가로 직접 예약 확정으로 대체) */
const PAYMENT_METHOD = (__ENV.PAYMENT_METHOD || 'POINT').toUpperCase();

/**
 * Spring Security form 로그인.
 * - POST body: application/x-www-form-urlencoded (username, password)
 * - redirects: 0 으로 302만 받고 리다이렉트 미수행 → Set-Cookie 가 담긴 응답에서 세션 쿠키 저장
 * - 성공 시 302 반환 (로그인 처리 후 리다이렉트)
 */
function login() {
  if (!TEST_USER || !TEST_PASS) return false;
  const body = `username=${encodeURIComponent(TEST_USER)}&password=${encodeURIComponent(TEST_PASS)}`;
  const res = http.post(`${BASE_URL}/login`, body, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    redirects: 0,
  });
  return res.status === 302 || (res.status >= 200 && res.status < 400);
}

export default function () {
  // 인증: 계정이 있으면 매 이터레이션마다 로그인 (같은 VU가 쿠키 유지)
  if (TEST_USER && TEST_PASS && !login()) {
    return;
  }

  // --- 1) 대기열 필요 여부 확인 (패턴 B: activation-threshold 초과 시에만 대기열 사용)
  const requiredRes = http.get(`${BASE_URL}/api/queue/required?concertId=${CONCERT_ID}`);
  const required = requiredRes.json('data.required') === true;
  let queueToken = null;

  if (required) {
    const enterRes = http.post(`${BASE_URL}/api/queue/enter?concertId=${CONCERT_ID}`, null);
    check(enterRes, { '대기열 진입': (r) => r.status === 201 });
    if (enterRes.status !== 201) return;
    queueToken = enterRes.json('data.token');
    // 입장 허용될 때까지 최대 60초, 1초 간격 폴링
    for (let i = 0; i < 60; i++) {
      const statusRes = http.get(`${BASE_URL}/api/queue/status?token=${queueToken}&concertId=${CONCERT_ID}`);
      if (statusRes.status === 200 && statusRes.json('data.isAllowed')) break;
      sleep(1);
    }
  }

  // --- 2) 좌석 목록 조회 (AVAILABLE 만 사용)
  const seatsRes = http.get(`${BASE_URL}/api/concerts/${CONCERT_ID}/seats`);
  check(seatsRes, { '좌석 조회': (r) => r.status === 200 });
  if (seatsRes.status !== 200) return;
  const seats = seatsRes.json('data') || [];
  const available = seats.filter((s) => s.status === 'AVAILABLE');
  if (available.length === 0) {
    sleep(1);
    return;
  }
  // VU마다 서로 다른 좌석 선택 (available[0]만 쓰면 전원이 같은 좌석을 잡아 409/429 폭증)
  const idx = Math.floor(Math.random() * available.length);
  const seatId = available[idx].id;

  // --- 3) 홀드 생성 (인증 필요; 서버는 200 또는 201 반환)
  const holdRes = http.post(`${BASE_URL}/api/holds`, JSON.stringify({
    concertId: Number(CONCERT_ID),
    seatId: Number(seatId),
  }), {
    headers: { 'Content-Type': 'application/json' },
  });
  const holdOk = holdRes.status === 200 || holdRes.status === 201;
  if (!holdOk) {
    const bodyPreview = (holdRes.body || '').slice(0, 200);
    console.warn(`[VU ${__VU}] 홀드 실패 status=${holdRes.status} body=${bodyPreview}`);
  }
  check(holdRes, { '홀드 생성': (r) => r.status === 200 || r.status === 201 });
  if (!holdOk) return;
  const holdToken = holdRes.json('data.holdToken');
  if (!holdToken) return;

  // --- 4) 결제 수단에 따른 분기
  if (PAYMENT_METHOD === 'POINT') {
    // 포인트 결제: 요청 → 승인(본문 없음) → 완료 (실제 포인트 차감)
    const reqRes = http.post(`${BASE_URL}/api/payments/request`, JSON.stringify({
      holdToken,
      paymentMethod: 'POINT',
    }), {
      headers: { 'Content-Type': 'application/json' },
    });
    check(reqRes, { '결제 요청': (r) => r.status === 201 });
    if (reqRes.status !== 201) return;
    const paymentKey = reqRes.json('data.paymentKey');
    if (!paymentKey) return;

    const approveRes = http.post(`${BASE_URL}/api/payments/${paymentKey}/approve`, '{}', {
      headers: { 'Content-Type': 'application/json' },
    });
    check(approveRes, { '결제 승인(포인트)': (r) => r.status === 200 });
    if (approveRes.status !== 200) return;

    const completeRes = http.post(`${BASE_URL}/api/payments/${paymentKey}/complete`, null);
    check(completeRes, { '결제 완료': (r) => r.status === 200 });
  } else {
    // CARD: k6에서는 토스 결제창 리다이렉트를 할 수 없으므로, 부하 검증용으로 직접 예약 확정만 수행
    const reserveRes = http.post(`${BASE_URL}/api/reservations`, JSON.stringify({
      holdToken,
    }), {
      headers: { 'Content-Type': 'application/json' },
    });
    check(reserveRes, { '예약 확정(CARD 대체)': (r) => r.status === 201 || r.status === 200 });
  }

  sleep(0.5);
}
