/**
 * 캐시(대기열 카운터 등) 핫 읽기: GET /api/queue/count 고빈도.
 *
 * --- Knee point / 병목 ---
 * - RPS를 많이 태울수록 Redis/네트워크·앱 처리 한계가 드러남. queue-flow와 겹쳐 보면 “읽기만” vs “진입+폴링” 비교 가능.
 * - 앱 CPU는 낮은데 지연이면 Redis 또는 네트워크 hop 의심.
 */
import http from 'k6/http';
import { check } from 'k6';

// [조정] count 전용이므로 VU·플래토를 크게 올리기 쉬움 — Redis/앱 한계까지
export const options = {
  stages: [
    { duration: '30s', target: 100 }, // [조정]
    { duration: '2m', target: 400 }, // [조정]
    { duration: '2m', target: 800 }, // [조정]
    { duration: '30s', target: 0 }, // [조정]
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CONCERT_ID = __ENV.CONCERT_ID || '1';

// [조정] VU 한 번 돌 때 count 호출 횟수: 클수록 RPS↑
const COUNTS_PER_ITERATION = 15;

export default function () {
  for (let i = 0; i < COUNTS_PER_ITERATION; i++) {
    const res = http.get(`${BASE_URL}/api/queue/count?concertId=${CONCERT_ID}`);
    check(res, { 'queue/count 200': (r) => r.status === 200 });
  }
}
