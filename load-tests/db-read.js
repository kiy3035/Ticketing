/**
 * DB 읽기 부하: 공연 카운트·목록·좌석 조회를 한 이터레이션에서 연속 호출.
 *
 * 부하 곡선·VU·시간: lib/stages.js (K6_PEAK_VU, K6_PEAK_HOLD, K6_PROFILE=stress 등)
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { authHeaders, baseUrl, concertId, jwtLogin } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

const DEFAULT_PEAK = 120;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {}),
  thresholds: pickThresholds({
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.1'],
  }),
};

const BASE = baseUrl();
const CID = concertId();

const LOOP_SLEEP_SEC = Math.max(0, parseFloat(__ENV.K6_LOOP_SLEEP_SEC || '0.2') || 0.2);

let loggedIn = false;

export default function () {
  if (!loggedIn) {
    if (!jwtLogin(BASE, __ENV.TEST_USER || '', __ENV.TEST_PASS || '')) {
      return;
    }
    loggedIn = true;
  }

  const counts = http.get(`${BASE}/api/concerts/counts`, { headers: authHeaders(false) });
  check(counts, { 'counts 200': (r) => r.status === 200 });

  const list = http.get(`${BASE}/api/concerts?past=false`, { headers: authHeaders(false) });
  check(list, { 'concerts list 200': (r) => r.status === 200 });

  const seats = http.get(`${BASE}/api/concerts/${CID}/seats`, { headers: authHeaders(false) });
  check(seats, { 'seats 200': (r) => r.status === 200 });

  sleep(LOOP_SLEEP_SEC);
}
