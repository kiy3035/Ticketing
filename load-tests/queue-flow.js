/**
 * 대기열: 진입 → 순번 폴링(입장 허용까지). 좌석 API는 인증 필요라 제외.
 *
 * --- Knee point / 병목 ---
 * - p95·에러와 함께 ticketing_queue_waiting_count, queue 관련 HTTP 지연이 오르면 입장·폴링 구간 병목.
 * - status 가 DB를 많이 타면(구현에 따라) DB 읽기와 겹쳐 보일 수 있음 → DB 메트릭·슬로우쿼리와 대조.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

// [조정] 단계별 최대 VU·유지 시간: 숫자만 올려가며 knee 구간 찾기
export const options = {
  stages: [
    { duration: '30s', target: 100 }, // [조정] 워밍업
    { duration: '2m', target: 200 }, // [조정]
    { duration: '2m', target: 300 }, // [조정]
    { duration: '2m', target: 400 }, // [조정]
    { duration: '2m', target: 600 }, // [조정] 최고 부하 플래토
    { duration: '30s', target: 0 }, // [조정] 램프다운
  ],
  // [조정] knee 기록용이면 완화, CI 게이트용이면 타이트하게
  thresholds: {
    http_req_duration: ['p(95)<5000'],
    http_req_failed: ['rate<0.1'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CONCERT_ID = __ENV.CONCERT_ID || '1';

// [조정] 폴링 최대 횟수(무한에 가깝게 두면 VU가 오래 잡힘)
const MAX_STATUS_POLLS = 50;
// [조정] 폴링 간격(초): 짧을수록 서버 RPS·Redis 부담 증가
const POLL_SLEEP_SEC = 1;

export default function () {
  const enterRes = http.post(`${BASE_URL}/api/queue/enter?concertId=${CONCERT_ID}`, null, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(enterRes, { '대기열 진입 201': (r) => r.status === 201 });
  if (enterRes.status !== 201) {
    return;
  }

  const token = enterRes.json('data.token');
  for (let i = 0; i < MAX_STATUS_POLLS; i++) {
    const statusRes = http.get(`${BASE_URL}/api/queue/status?token=${token}&concertId=${CONCERT_ID}`);
    check(statusRes, { '순번 조회 200': (r) => r.status === 200 });
    if (statusRes.status === 200 && statusRes.json('data.isAllowed')) {
      break;
    }
    sleep(POLL_SLEEP_SEC);
  }
}
