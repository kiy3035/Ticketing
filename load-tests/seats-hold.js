/**
 * 좌석 & 홀드: 로그인 → 좌석 목록 → 홀드 생성 → DELETE 로 홀드 취소(반복 부하용).
 * 예약·결제 확정까지 보려면 full-flow.js 사용.
 *
 * --- Knee point / 병목 ---
 * - ticketing_lock_acquire_failures_total, 홀드 API p95가 같이 튀면 좌석 락/Redis 경합 쪽 힌트.
 * - 좌석 조회만 느리면 DB/쿼리·풀 쪽을 의심.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId, formLogin } from './lib/common.js';

// VU 시작 시 한 번만 로그인. 매 이터레이션 로그인 시 30 VU가 같은 계정을 쓰면
// Spring Session Fixation Protection 이 이전 세션을 무효화해 DELETE 등이 401로 실패함.
let loggedIn = false;

// [조정] 홀드 경합을 보려면 VU·플래토 시간을 올려 같은 공연·좌석 풀에 걸기
export const options = {
  stages: [
    { duration: '30s', target: 10 }, // [조정]
    { duration: '1m', target: 30 }, // [조정]
    { duration: '1m', target: 50 }, // [조정]
    { duration: '30s', target: 0 }, // [조정]
  ],
  thresholds: {
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.2'],
  },
};

const BASE = baseUrl();
const CID = concertId();
const TEST_USER = __ENV.TEST_USER || '';
const TEST_PASS = __ENV.TEST_PASS || '';

export default function () {
  if (!loggedIn) {
    if (!formLogin(BASE, TEST_USER, TEST_PASS)) {
      return;
    }
    loggedIn = true;
  }

  const seatsRes = http.get(`${BASE}/api/concerts/${CID}/seats`);
  check(seatsRes, { '좌석 조회 200': (r) => r.status === 200 });
  if (seatsRes.status !== 200) {
    return;
  }

  const seats = seatsRes.json('data') || [];
  const available = seats.filter((s) => s.status === 'AVAILABLE');
  if (available.length === 0) {
    sleep(1); // [조정] 좌석 없을 때 대기
    return;
  }

  const idx = Math.floor(Math.random() * available.length);
  const seatId = available[idx].id;

  const holdRes = http.post(
    `${BASE}/api/holds`,
    JSON.stringify({ concertId: Number(CID), seatId: Number(seatId) }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const holdOk = holdRes.status === 200 || holdRes.status === 201;
  check(holdRes, { '홀드 생성': (r) => r.status === 200 || r.status === 201 });
  if (!holdOk) {
    return;
  }

  const holdToken = holdRes.json('data.holdToken');
  if (!holdToken) {
    return;
  }

  const delRes = http.del(`${BASE}/api/holds/${holdToken}`);
  check(delRes, { '홀드 취소 204': (r) => r.status === 204 });
  sleep(0.3); // [조정] 이터레이션 간 간격
}
