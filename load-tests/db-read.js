/**
 * DB 읽기 부하: 공연 카운트·목록·좌석 조회를 한 이터레이션에서 연속 호출.
 *
 * --- Knee point / 병목 ---
 * - 이 스크립트만 돌릴 때 p95가 먼저 나빠지면 커넥션 풀·슬로우쿼리·DB CPU/IO 가능성.
 * - 다른 스크립트는 괜찮은데 이것만 나쁘면 “읽기 쿼리/인덱스” 쪽을 우선 의심.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId, formLogin } from './lib/common.js';

// [조정] 읽기 전용이므로 VU를 크게 올려도 쓰기 락 경합은 상대적으로 적음 — DB 한계까지 밀어볼 때 유리
export const options = {
  stages: [
    { duration: '30s', target: 20 }, // [조정]
    { duration: '2m', target: 80 }, // [조정]
    { duration: '1m', target: 120 }, // [조정]
    { duration: '30s', target: 0 }, // [조정]
  ],
  thresholds: {
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE = baseUrl();
const CID = concertId();

// [조정] 한 루프 끝난 뒤 쉼: 짧을수록 RPS↑·DB 부담↑
const LOOP_SLEEP_SEC = 0.2;

// VU 시작 시 한 번만 로그인 (매 이터레이션 로그인 시 세션 충돌 방지)
let loggedIn = false;

export default function () {
  if (!loggedIn) {
    if (!formLogin(BASE, __ENV.TEST_USER || '', __ENV.TEST_PASS || '')) {
      return;
    }
    loggedIn = true;
  }

  const counts = http.get(`${BASE}/api/concerts/counts`);
  check(counts, { 'counts 200': (r) => r.status === 200 });

  const list = http.get(`${BASE}/api/concerts?past=false`);
  check(list, { 'concerts list 200': (r) => r.status === 200 });

  const seats = http.get(`${BASE}/api/concerts/${CID}/seats`);
  check(seats, { 'seats 200': (r) => r.status === 200 });

  sleep(LOOP_SLEEP_SEC);
}
