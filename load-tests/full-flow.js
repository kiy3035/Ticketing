/**
 * k6 풀 플로우 부하 테스트: 대기열 진입 → 입장 허용 → 좌석 조회 → 홀드 → 예약 확정
 * 인증이 필요하므로 TEST_USER, TEST_PASS 환경 변수로 로그인 후 시나리오 실행.
 *
 * 실행 예:
 *   k6 run -e BASE_URL=http://localhost:8080 -e TEST_USER=user1 -e TEST_PASS=pass1 -e CONCERT_ID=1 full-flow.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 30 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.2'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CONCERT_ID = __ENV.CONCERT_ID || '1';
const TEST_USER = __ENV.TEST_USER || '';
const TEST_PASS = __ENV.TEST_PASS || '';

function login() {
  if (!TEST_USER || !TEST_PASS) return false;
  const res = http.post(`${BASE_URL}/login`, {
    username: TEST_USER,
    password: TEST_PASS,
  }, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  });
  return res.status >= 200 && res.status < 400;
}

export default function () {
  if (TEST_USER && TEST_PASS && !login()) {
    return;
  }

  // 1) 대기열 필요 여부 확인
  const requiredRes = http.get(`${BASE_URL}/api/queue/required?concertId=${CONCERT_ID}`);
  const required = requiredRes.json('data.required') === true;
  let queueToken = null;

  if (required) {
    const enterRes = http.post(`${BASE_URL}/api/queue/enter?concertId=${CONCERT_ID}`, null);
    check(enterRes, { '대기열 진입': (r) => r.status === 201 });
    if (enterRes.status !== 201) return;
    queueToken = enterRes.json('data.token');
    for (let i = 0; i < 60; i++) {
      const statusRes = http.get(`${BASE_URL}/api/queue/status?token=${queueToken}&concertId=${CONCERT_ID}`);
      if (statusRes.status === 200 && statusRes.json('data.isAllowed')) break;
      sleep(1);
    }
  }

  // 2) 좌석 목록 조회
  const seatsRes = http.get(`${BASE_URL}/api/concerts/${CONCERT_ID}/seats`);
  check(seatsRes, { '좌석 조회': (r) => r.status === 200 });
  if (seatsRes.status !== 200) return;
  const seats = seatsRes.json('data') || [];
  const available = seats.filter((s) => s.status === 'AVAILABLE');
  if (available.length === 0) {
    sleep(1);
    return;
  }
  const seatId = available[0].id;

  // 3) 홀드 생성
  const holdRes = http.post(`${BASE_URL}/api/holds`, JSON.stringify({
    concertId: Number(CONCERT_ID),
    seatId: Number(seatId),
  }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(holdRes, { '홀드 생성': (r) => r.status === 201 });
  if (holdRes.status !== 201) return;
  const holdToken = holdRes.json('data.holdToken');
  if (!holdToken) return;

  // 4) 예약 확정
  const reserveRes = http.post(`${BASE_URL}/api/reservations`, JSON.stringify({
    holdToken,
  }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(reserveRes, { '예약 확정': (r) => r.status === 201 || r.status === 200 });

  sleep(0.5);
}
