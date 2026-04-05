/**
 * 대기열: 진입 → 순번 폴링(입장 허용까지). 좌석 API는 인증 필요라 제외.
 *
 * 기본 곡선: 약 1분(짧게 램프업 → 피크 유지) + 폴링 간격 짧게 해 RPS를 크게 만든다.
 * 단계 시간은 K6_WARM_DURATION 등 env로 덮어쓴다 (lib/stages.js).
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

// 미설정 시 피크 VU. 부하가 과하면 K6_PEAK_VU로 낮춘다.
const DEFAULT_PEAK = 400;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {
    warmDur: '5s',
    midDur: '5s',
    climbDur: '10s',
    peakHold: '35s',
    rampDown: '5s',
  }),
  thresholds: pickThresholds({
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.1'],
  }),
};

const BASE = baseUrl();
const CID = concertId();

const MAX_STATUS_POLLS = parseInt(__ENV.K6_QUEUE_MAX_POLL || '50', 10) || 50;
// 기본 0.1s: 폴링 RPS↑. 더 세게는 0.05 또는 0 (서버·Redis 주의)
const POLL_SLEEP_SEC = parseFloat(__ENV.K6_QUEUE_POLL_SLEEP_SEC || '0.1') || 0.1;

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
