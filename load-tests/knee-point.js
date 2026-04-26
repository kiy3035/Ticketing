/**
 * Knee Point 탐지 — 대기열 플로우 (queue-flow.js와 동일 시나리오)
 *
 * 목적: VU=800(안정)~VU=1500(불안정) 구간을 계단식으로 스캔해
 *       RPS가 꺾이고 에러율이 올라오는 knee point를 찾는다.
 *
 * 실행:
 *   k6 run \
 *     -e BASE_URL=http://<nginx_ip>:80 \
 *     -e CONCERT_ID=<id> \
 *     -e K6_QUEUE_POLL_SLEEP_SEC=0.005 \
 *     load-tests/knee-point.js
 *
 * 총 소요시간: 약 5분 30초
 * 관측 포인트: Grafana RPS 곡선이 평탄해지거나 꺾이는 VU 구간
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId } from './lib/common.js';

export const options = {
  stages: [
    { duration: '1m',  target: 500  },  // 워밍업 — JVM warm + 안정 베이스라인
    { duration: '1m',  target: 800  },  // Phase 3에서 에러 0% 확인한 구간
    { duration: '1m',  target: 1000 },  // 탐색 구간
    { duration: '1m',  target: 1200 },  // 탐색 구간
    { duration: '1m',  target: 1500 },  // Phase 4에서 에러 3.41% 확인한 구간
    { duration: '30s', target: 0    },  // 쿨다운
  ],
  thresholds: {
    // knee point 탐지용 — 에러가 나도 테스트 중단하지 않음
    http_req_duration: ['p(95)<120000'],
    http_req_failed:   ['rate<1'],
  },
};

const BASE = baseUrl();
const CID  = concertId();

const POLL_SLEEP_SEC = parseFloat(__ENV.K6_QUEUE_POLL_SLEEP_SEC || '0.1');
// VU=1500 기준 큐 드레인 60초. 서버 빠를 때(~70ms/poll) 기준 800+ polls 필요
const MAX_POLLS      = parseInt(__ENV.K6_QUEUE_MAX_POLL || '1000', 10);

export default function () {
  const enterRes = http.post(`${BASE}/api/queue/enter?concertId=${CID}`, null, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(enterRes, { '대기열 진입 201': (r) => r.status === 201 });
  if (enterRes.status !== 201) {
    sleep(5);
    return;
  }

  const token = enterRes.json('data.token');
  for (let i = 0; i < MAX_POLLS; i++) {
    const statusRes = http.get(`${BASE}/api/queue/status?token=${token}&concertId=${CID}`);
    check(statusRes, { '순번 조회 200': (r) => r.status === 200 });
    if (statusRes.status === 200 && statusRes.json('data.isAllowed')) break;
    sleep(POLL_SLEEP_SEC);
  }
}
