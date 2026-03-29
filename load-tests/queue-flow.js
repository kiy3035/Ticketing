/**
 * 대기열: 진입 → 순번 폴링(입장 허용까지). 좌석 API는 인증 필요라 제외.
 *
 * 부하 곡선·VU·시간: lib/stages.js (K6_PEAK_VU, K6_PEAK_HOLD, K6_PROFILE=stress 등)
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

const DEFAULT_PEAK = 600;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {
    peakHold: '3m',
    climbDur: '2m30s',
  }),
  thresholds: pickThresholds({
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.1'],
  }),
};

const BASE = baseUrl();
const CID = concertId();

const MAX_STATUS_POLLS = parseInt(__ENV.K6_QUEUE_MAX_POLL || '50', 10) || 50;
const POLL_SLEEP_SEC = parseFloat(__ENV.K6_QUEUE_POLL_SLEEP_SEC || '1') || 1;

export default function () {
  const enterRes = http.post(`${BASE}/api/queue/enter?concertId=${CID}`, null, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(enterRes, { '대기열 진입 201': (r) => r.status === 201 });
  if (enterRes.status !== 201) {
    return;
  }

  const token = enterRes.json('data.token');
  for (let i = 0; i < MAX_STATUS_POLLS; i++) {
    const statusRes = http.get(`${BASE}/api/queue/status?token=${token}&concertId=${CID}`);
    check(statusRes, { '순번 조회 200': (r) => r.status === 200 });
    if (statusRes.status === 200 && statusRes.json('data.isAllowed')) {
      break;
    }
    sleep(POLL_SLEEP_SEC);
  }
}
