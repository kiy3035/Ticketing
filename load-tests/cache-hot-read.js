/**
 * 캐시(대기열 카운터 등) 핫 읽기: GET /api/queue/count 고빈도.
 *
 * 부하 곡선·VU·시간: lib/stages.js (K6_PEAK_VU, K6_PEAK_HOLD, K6_PROFILE=stress 등)
 * 이터당 호출: K6_COUNTS_PER_ITER (기본 15)
 */
import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, concertId } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

const DEFAULT_PEAK = 200;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {
    peakHold: '2m30s',
  }),
  thresholds: pickThresholds({
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.1'],
  }),
};

const BASE = baseUrl();
const CID = concertId();

const COUNTS_PER_ITERATION = Math.max(
  1,
  parseInt(__ENV.K6_COUNTS_PER_ITER || '15', 10) || 15,
);

export default function () {
  for (let i = 0; i < COUNTS_PER_ITERATION; i++) {
    const res = http.get(`${BASE}/api/queue/count?concertId=${CID}`);
    check(res, { 'queue/count 200': (r) => r.status === 200 });
  }
}
