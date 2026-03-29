/**
 * 캐시(대기열 카운터 등) 핫 읽기: GET /api/queue/count 고빈도.
 *
 * 피크 VU: -e K6_CACHE_HOT_PEAK_VU=200 (기본 150). 소형 인스턴스는 낮추고, knee 탐색 시만 올린다.
 * 이터당 호출 수: 아래 COUNTS_PER_ITERATION 상수.
 *
 * --- Knee point / 병목 ---
 * - RPS를 많이 태울수록 Redis·앱 처리 한계가 드러남. queue-flow와 겹쳐 “읽기만” vs “진입+폴링” 비교.
 * - 요청이 서버에 도달해야 Prometheus http_server_requests_* 가 쌓인다(앱 미기동·과부하·네트워크면 0에 가깝게 보일 수 있음).
 */
import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, concertId } from './lib/common.js';

const PEAK_VU = Math.max(10, parseInt(__ENV.K6_CACHE_HOT_PEAK_VU || '150', 10) || 150);
const WARM_VU = Math.max(5, Math.floor(PEAK_VU / 6));
const MID_VU = Math.max(WARM_VU, Math.floor(PEAK_VU / 2));

export const options = {
  stages: [
    { duration: '30s', target: WARM_VU },
    { duration: '90s', target: MID_VU },
    { duration: '2m', target: PEAK_VU },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE = baseUrl();
const CID = concertId();

// [조정] 이터당 count 호출 횟수 → RPS에 곱해짐
const COUNTS_PER_ITERATION = 15;

export default function () {
  for (let i = 0; i < COUNTS_PER_ITERATION; i++) {
    const res = http.get(`${BASE}/api/queue/count?concertId=${CID}`);
    check(res, { 'queue/count 200': (r) => r.status === 200 });
  }
}
