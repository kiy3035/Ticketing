/**
 * API 건강: Actuator 헬스 + 가벼운 공개 API(대기열 필요 여부).
 *
 * 부하 곡선·VU·시간: lib/stages.js (K6_PEAK_VU, K6_PEAK_HOLD, K6_PROFILE=stress 등)
 */
import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, concertId } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

const DEFAULT_PEAK = 50;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {
    warmDur: '25s',
    midDur: '60s',
    peakHold: '90s',
    rampDown: '30s',
  }),
  thresholds: pickThresholds({
    http_req_duration: ['p(95)<3000'],
    http_req_failed: ['rate<0.05'],
  }),
};

const BASE = baseUrl();
const CID = concertId();

export default function () {
  const h = http.get(`${BASE}/actuator/health`);
  check(h, { 'health 200': (r) => r.status === 200 });

  const q = http.get(`${BASE}/api/queue/required?concertId=${CID}`);
  check(q, { 'queue/required 200': (r) => r.status === 200 });
}
