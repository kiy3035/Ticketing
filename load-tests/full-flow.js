/**
 * Full-flow (E2E): 대기열 필요 시 진입·폴링 → 좌석 조회 → 홀드 → 결제 → 예약 확정.
 * 결제 수단은 코드상 POINT 로만 고정한다. 카드 등은 위젯/리다이렉트로 k6 자동화가 불가하므로 부하 스크립트에서 변경하지 말 것.
 *
 * --- Knee point / 병목 ---
 * - Knee: 단계(stages)의 target(VU)를 올릴수록 k6 요약의 http_req_duration p95·http_req_failed 가
 *   “갑자기” 나빠지기 시작하는 구간을 기록한다.
 * - 병목 힌트(동시에 Grafana/Prometheus): 대기열·HTTP만 튀면 입장/폴링 구간,
 *   ticketing_lock_acquire_failures_total 가 홀드와 함께 오르면 좌석 락/Redis 경합,
 *   DB 커넥션/슬로우쿼리는 응답이 전반적으로 지연되면서 여러 URI에서 동반 상승하는 패턴이 많다.
 *   → docs/monitoring.md 메트릭과 대조.
 *
 * 실행: k6 run -e BASE_URL=... -e CONCERT_ID=... -e TEST_USER=... -e TEST_PASS=... load-tests/full-flow.js
 *
 * --- 대시보드 6패널을 full-flow만으로 채우기 ---
 * - 대기열 인원: 앱 설정상 queue required 가 false 이면 0이 정상. 쌓이게 하려면 queue-flow 병행 또는 activation-threshold 조정.
 * - 캐시 RPS: 기본 full-flow 는 /api/queue/count 를 거의 안 둠. Grafana 맞추려면 -e K6_EXTRA_QUEUE_COUNT=15 처럼 이터레이션마다 count 호출 횟수 지정.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId, formLogin } from './lib/common.js';

// [조정] t3a.small x2(k6·앱) 기준으로 이전보다 강하게. CPU 크레딧·포인트·좌석 고갈 시 단계·VU 낮추기.
export const options = {
  stages: [
    { duration: '30s', target: 25 }, // [조정] 워밍업
    { duration: '90s', target: 60 }, // [조정] 플래토1
    { duration: '2m', target: 100 }, // [조정] 플래토2
    { duration: '90s', target: 140 }, // [조정] 피크 (너무 세면 여기 target 만 줄이기)
    { duration: '45s', target: 0 }, // [조정] 램프다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<8000'],
    http_req_failed: ['rate<0.35'],
  },
};

const BASE = baseUrl();
const CID = concertId();
const TEST_USER = __ENV.TEST_USER || '';
const TEST_PASS = __ENV.TEST_PASS || '';
const K6_EXTRA_QUEUE_COUNT = Math.max(0, parseInt(__ENV.K6_EXTRA_QUEUE_COUNT || '0', 10) || 0);

/** 부하 테스트에서 허용하는 결제 수단은 포인트뿐. CARD 등으로 바꾸지 말 것(k6·토스 위젯 불가). */
const PAYMENT_METHOD_POINT = 'POINT';

// VU 시작 시 한 번만 로그인 (매 이터레이션 로그인 시 세션 충돌 방지)
let loggedIn = false;

export default function () {
  if (!loggedIn) {
    if (!formLogin(BASE, TEST_USER, TEST_PASS)) {
      return;
    }
    loggedIn = true;
  }

  const requiredRes = http.get(`${BASE}/api/queue/required?concertId=${CID}`);
  const required = requiredRes.json('data.required') === true;

  if (required) {
    const enterRes = http.post(`${BASE}/api/queue/enter?concertId=${CID}`, null);
    check(enterRes, { '대기열 진입': (r) => r.status === 201 });
    if (enterRes.status !== 201) return;
    const queueToken = enterRes.json('data.token');
    // [조정] 최대 폴링 횟수·간격: 대기열 압력·타임아웃 민감도
    const maxPoll = 60;
    const pollSleepSec = 1;
    for (let i = 0; i < maxPoll; i++) {
      const statusRes = http.get(`${BASE}/api/queue/status?token=${queueToken}&concertId=${CID}`);
      if (statusRes.status === 200 && statusRes.json('data.isAllowed')) break;
      sleep(pollSleepSec);
    }
  }

  const seatsRes = http.get(`${BASE}/api/concerts/${CID}/seats`);
  check(seatsRes, { '좌석 조회': (r) => r.status === 200 });
  if (seatsRes.status !== 200) return;
  const seats = seatsRes.json('data') || [];
  const available = seats.filter((s) => s.status === 'AVAILABLE');
  if (available.length === 0) {
    sleep(1);
    return;
  }
  const idx = Math.floor(Math.random() * available.length);
  const seatId = available[idx].id;

  const holdRes = http.post(
    `${BASE}/api/holds`,
    JSON.stringify({ concertId: Number(CID), seatId: Number(seatId) }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const holdOk = holdRes.status === 200 || holdRes.status === 201;
  check(holdRes, { '홀드 생성': (r) => r.status === 200 || r.status === 201 });
  if (!holdOk) return;
  const holdToken = holdRes.json('data.holdToken');
  if (!holdToken) return;

  const reqRes = http.post(
    `${BASE}/api/payments/request`,
    JSON.stringify({ holdToken, paymentMethod: PAYMENT_METHOD_POINT }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(reqRes, { '결제 요청': (r) => r.status === 201 });
  if (reqRes.status !== 201) return;
  const paymentKey = reqRes.json('data.paymentKey');
  if (!paymentKey) return;

  const approveRes = http.post(`${BASE}/api/payments/${paymentKey}/approve`, '{}', {
    headers: { 'Content-Type': 'application/json' },
  });
  check(approveRes, { '결제 승인(포인트)': (r) => r.status === 200 });
  if (approveRes.status !== 200) return;

  const completeRes = http.post(`${BASE}/api/payments/${paymentKey}/complete`, null);
  check(completeRes, { '결제 완료': (r) => r.status === 200 });

  // [조정] Grafana「캐시 핫리드」패널용: /api/queue/count 고빈도 (0 이면 호출 안 함)
  for (let c = 0; c < K6_EXTRA_QUEUE_COUNT; c++) {
    http.get(`${BASE}/api/queue/count?concertId=${CID}`);
  }

  // [조정] 이터레이션 간 간격(너무 짧으면 동일 계정/포인트 고갈·좌석 부족 빨리 옴)
  sleep(0.35);
}
