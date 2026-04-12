/**
 * 대기열: 진입 → 순번 폴링(입장 허용까지). 좌석 API는 인증 필요라 제외.
 *
 * --- 기본값(이 파일) — 단일 앱 서버 스펙에 맞춘 "용량 봉투" 탐색용 ---
 * - 피크 VU **200**, 폴링 **0.08s**, 피크 유지 **45s** → Grafana/Prometheus에 피크 구간이 남기 쉬움.
 * - 더 세게(한계/knee) 보던 기존 실험은 **env로만** 올린다 (스크립트 기본은 과부하 아님).
 *
 * --- 기존 pool 10→30→30+VT 스트레스 런과 동일하게 재현하려면 ---
 *   -e K6_PEAK_VU=800 -e K6_QUEUE_POLL_SLEEP_SEC=0.005 \
 *   -e K6_WARM_DURATION=5s -e K6_MID_DURATION=5s -e K6_CLIMB_DURATION=10s \
 *   -e K6_PEAK_HOLD=35s -e K6_RAMP_DOWN=5s
 *
 * 단계·피크는 K6_* env로 덮어쓴다 (lib/stages.js).
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

/** 미설정 시 피크 VU. 계단 실험은 100→150→200 처럼 K6_PEAK_VU만 바꿔도 됨. */
const DEFAULT_PEAK = 200;

export const options = {
  stages: buildRampPeakStages(DEFAULT_PEAK, {
    warmDur: '10s',
    midDur: '10s',
    climbDur: '15s',
    peakHold: '45s',
    rampDown: '10s',
  }),
  thresholds: pickThresholds({
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.1'],
  }),
};

const BASE = baseUrl();
const CID = concertId();

const MAX_STATUS_POLLS = parseInt(__ENV.K6_QUEUE_MAX_POLL || '50', 10) || 50;
/** 기본 0.08s: 1대 기준에서 RPS 폭주 완화. 스트레스는 0.005 등으로 env 지정. */
const POLL_SLEEP_SEC = parseFloat(__ENV.K6_QUEUE_POLL_SLEEP_SEC || '0.08') || 0.08;

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
