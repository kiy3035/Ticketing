/**
 * Full-flow (E2E): 대기열 필요 시 진입·폴링 → 좌석 조회 → 홀드 → 결제 → 예약 확정.
 * 결제 수단은 POINT 고정(k6·토스 위젯 불가).
 *
 * 부하 곡선·VU·시간: lib/stages.js (K6_PEAK_VU, K6_PEAK_HOLD, K6_PROFILE=stress 등)
 * 캐시 패널용 count N회: K6_EXTRA_QUEUE_COUNT
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId, formLogin } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

const DEFAULT_PEAK = 140;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {
    peakHold: '2m30s',
  }),
  thresholds: pickThresholds(
    {
      http_req_duration: ['p(95)<8000'],
      http_req_failed: ['rate<0.35'],
    },
    {
      http_req_duration: ['p(95)<120000'],
      http_req_failed: ['rate<1'],
    },
  ),
};

const BASE = baseUrl();
const CID = concertId();
const TEST_USER = __ENV.TEST_USER || '';
const TEST_PASS = __ENV.TEST_PASS || '';
const K6_EXTRA_QUEUE_COUNT = Math.max(0, parseInt(__ENV.K6_EXTRA_QUEUE_COUNT || '0', 10) || 0);

const PAYMENT_METHOD_POINT = 'POINT';
const FLOW_SLEEP_SEC = Math.max(0, parseFloat(__ENV.K6_FLOW_SLEEP_SEC || '0.35') || 0.35);

let loggedIn = false;

export default function () {
  if (!loggedIn) {
    if (!formLogin(BASE, TEST_USER, TEST_PASS)) {
      return;
    }
    loggedIn = true;
  }

  const requiredRes = http.get(`${BASE}/api/queue/required?concertId=${CID}`);
  const required = requiredRes.json('data.required') === true;

  if (required) {
    const enterRes = http.post(`${BASE}/api/queue/enter?concertId=${CID}`, null);
    check(enterRes, { '대기열 진입': (r) => r.status === 201 });
    if (enterRes.status !== 201) return;
    const queueToken = enterRes.json('data.token');
    const maxPoll = parseInt(__ENV.K6_QUEUE_MAX_POLL || '60', 10) || 60;
    const pollSleepSec = parseFloat(__ENV.K6_QUEUE_POLL_SLEEP_SEC || '1') || 1;
    for (let i = 0; i < maxPoll; i++) {
      const statusRes = http.get(`${BASE}/api/queue/status?token=${queueToken}&concertId=${CID}`);
      if (statusRes.status === 200 && statusRes.json('data.isAllowed')) break;
      sleep(pollSleepSec);
    }
  }

  const seatsRes = http.get(`${BASE}/api/concerts/${CID}/seats`);
  check(seatsRes, { '좌석 조회': (r) => r.status === 200 });
  if (seatsRes.status !== 200) return;
  const seats = seatsRes.json('data') || [];
  const available = seats.filter((s) => s.status === 'AVAILABLE');
  if (available.length === 0) {
    sleep(1);
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
  if (!holdOk) return;
  const holdToken = holdRes.json('data.holdToken');
  if (!holdToken) return;

  const reqRes = http.post(
    `${BASE}/api/payments/request`,
    JSON.stringify({ holdToken, paymentMethod: PAYMENT_METHOD_POINT }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(reqRes, { '결제 요청': (r) => r.status === 201 });
  if (reqRes.status !== 201) return;
  const paymentKey = reqRes.json('data.paymentKey');
  if (!paymentKey) return;

  const approveRes = http.post(`${BASE}/api/payments/${paymentKey}/approve`, '{}', {
    headers: { 'Content-Type': 'application/json' },
  });
  check(approveRes, { '결제 승인(포인트)': (r) => r.status === 200 });
  if (approveRes.status !== 200) return;

  const completeRes = http.post(`${BASE}/api/payments/${paymentKey}/complete`, null);
  check(completeRes, { '결제 완료': (r) => r.status === 200 });

  for (let c = 0; c < K6_EXTRA_QUEUE_COUNT; c++) {
    http.get(`${BASE}/api/queue/count?concertId=${CID}`);
  }

  sleep(FLOW_SLEEP_SEC);
}
