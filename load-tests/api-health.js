/**
 * API 건강: Actuator 헬스 + 가벼운 공개 API(대기열 필요 여부).
 *
 * --- Knee point / 병목 ---
 * - 여기서 p95·에러가 먼저 깨지면 앱 전체 가용성·스레드·다운스트림 공통 병목 가능성.
 * - 대기열만 이상하면 queue/required 경로 또는 그 뒤 Redis를 의심.
 */
import http from 'k6/http';
import { check } from 'k6';

// [조정] VU 램프: 합성 헬스는 보통 낮은 VU부터 올려도 됨
export const options = {
  stages: [
    { duration: '30s', target: 20 }, // [조정]
    { duration: '1m', target: 50 }, // [조정]
    { duration: '30s', target: 0 }, // [조정] 램프다운
  ],
  // [조정] 헬스는 빨라야 하므로 ms·에러율 기준은 환경에 맞게
  thresholds: {
    http_req_duration: ['p(95)<3000'],
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CONCERT_ID = __ENV.CONCERT_ID || '1';

export default function () {
  const h = http.get(`${BASE_URL}/actuator/health`);
  check(h, { 'health 200': (r) => r.status === 200 });

  const q = http.get(`${BASE_URL}/api/queue/required?concertId=${CONCERT_ID}`);
  check(q, { 'queue/required 200': (r) => r.status === 200 });
}
