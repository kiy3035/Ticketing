/**
 * 좌석 & 홀드: 로그인 → 좌석 목록 → 홀드 생성 → DELETE 로 홀드 취소(반복 부하용).
 *
 * 락 실패율을 내고 싶으면 K6_HOT_SEAT_ID 로 한 좌석만 공유(좌석 풀이 커서 랜덤이 분산될 때).
 *
 * 부하 곡선·VU·시간: lib/stages.js (K6_PEAK_VU, K6_PEAK_HOLD, K6_PROFILE=stress 등)
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId, formLogin } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

const DEFAULT_PEAK = 60;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {
    midDur: '75s',
    peakHold: '2m',
  }),
  thresholds: pickThresholds({
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.2'],
  }),
};

const BASE = baseUrl();
const CID = concertId();
const TEST_USER = __ENV.TEST_USER || '';
const TEST_PASS = __ENV.TEST_PASS || '';

const ITER_SLEEP_SEC = Math.max(0, parseFloat(__ENV.K6_ITER_SLEEP_SEC || '0.3') || 0.3);

function parseHotSeatId() {
  const raw = __ENV.K6_HOT_SEAT_ID;
  if (raw === undefined || String(raw).trim() === '') {
    return NaN;
  }
  const n = parseInt(String(raw).trim(), 10);
  return Number.isFinite(n) && n >= 1 ? n : NaN;
}

const HOT_SEAT_ID = parseHotSeatId();

let loggedIn = false;

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

  let seatId;
  if (Number.isFinite(HOT_SEAT_ID)) {
    // 동일 좌석 집중 → 락·홀드 경합(및 실패율) 관측용. AVAILABLE 여부와 무관하게 시도한다.
    seatId = HOT_SEAT_ID;
  } else {
    const available = seats.filter((s) => s.status === 'AVAILABLE');
    if (available.length === 0) {
      sleep(1);
      return;
    }
    const idx = Math.floor(Math.random() * available.length);
    seatId = available[idx].id;
  }

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
  sleep(ITER_SLEEP_SEC);
}
