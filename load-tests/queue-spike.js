/**
 * 대기열 스파이크·knee point 탐색 전용. 단시간 트래픽 폭증으로 빠르게 한계 확인.
 *
 * 기존 queue-flow.js와 차이:
 *  - 램프업 최소화 (15s 즉시 스파이크)
 *  - 폴링 제거 → enter 처리량·에러율에만 집중
 *  - 전체 소요: 약 2~3분
 *
 * 단계별 탐색 예시 (batch-size 바꾸며 재실행):
 *   1회차(기준): K6_PEAK_VU=300
 *   2회차:       K6_PEAK_VU=600
 *   3회차:       K6_PEAK_VU=1000
 *   → 에러율 급증·p95 꺾이는 VU 수 = knee point
 *
 * 실행:
 *   k6 run -e BASE_URL=http://<EC2>:8080 -e CONCERT_ID=1 load-tests/queue-spike.js
 *   k6 run -e BASE_URL=... -e CONCERT_ID=1 -e K6_PEAK_VU=1000 -e K6_PROFILE=stress load-tests/queue-spike.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, concertId } from './lib/common.js';
import { peakVu, pickThresholds } from './lib/stages.js';

const DEFAULT_PEAK = 300;
const peak = peakVu(DEFAULT_PEAK);

export const options = {
  // 즉시 스파이크: 15s 내 피크 → 90s 유지 → 15s 감소. 총 ~2분
  stages: [
    { duration: '15s', target: peak },
    { duration: '90s', target: peak },
    { duration: '15s', target: 0 },
  ],
  thresholds: pickThresholds(
    {
      // 정상 임계치: enter p95 < 500ms, 에러 < 5%
      'http_req_duration{name:enter}': ['p(95)<500'],
      http_req_failed: ['rate<0.05'],
    },
    {
      // stress 모드: 한계까지 밀어붙이기, 임계치 거의 무제한
      'http_req_duration{name:enter}': ['p(95)<30000'],
      http_req_failed: ['rate<1'],
    },
  ),
};

const BASE = baseUrl();
const CID = concertId();

export default function () {
  // enter만 측정 (폴링 없음 → 처리량 순수 확인)
  const res = http.post(
    `${BASE}/api/queue/enter?concertId=${CID}`,
    null,
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'enter' },
    },
  );
  check(res, { '대기열 진입 201': (r) => r.status === 201 });
}
