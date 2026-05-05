/**
 * 페일오버용 retry-with-backoff 시나리오 (Phase 8 개선판).
 *
 * queue-flow.js와 동일한 흐름이지만, 502/503/504/timeout/connection-error 응답에 대해
 * 클라이언트가 지수 백오프로 자동 재시도한다.
 *   - 첫 시도 실패 → 100ms 대기 후 재시도
 *   - 두 번째도 실패 → 200ms 대기 후 재시도
 *   - 세 번째도 실패 → 포기 (사용자 에러로 카운트)
 *
 * 멱등성: GET /api/queue/status는 안전, POST /api/queue/enter는 토큰 발급 중복이
 * 발생할 수 있으나 사용자 경험상 마지막 토큰만 유효하므로 무해.
 * (실제 결제·홀드처럼 위험한 POST는 Idempotency-Key로 중복 방지되어 있음.)
 *
 * Phase 8 비교 측정용 — kill 동안 발생하는 사용자 에러를
 * 서버 측 nginx 격리 + 클라이언트 측 retry 두 층으로 흡수해 1% 미만 목표.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, concertId } from './lib/common.js';
import { buildRampPeakStages, pickThresholds } from './lib/stages.js';

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
const POLL_SLEEP_SEC = parseFloat(__ENV.K6_QUEUE_POLL_SLEEP_SEC || '0.1') || 0.1;

// retry 파라미터: 페일오버 시 nginx 격리 지연(~2s) 동안 흡수할 수 있을 정도로 설정
const MAX_RETRIES = parseInt(__ENV.K6_RETRY_MAX || '2', 10);
const BACKOFF_BASE_MS = parseInt(__ENV.K6_RETRY_BASE_MS || '100', 10);

/**
 * 재시도 가능한 실패인지 판별.
 * - 5xx: 서버 측 에러 (502/503/504 — nginx가 죽은 upstream 라우팅 시 발생)
 * - status === 0: connection refused / timeout / EOF
 * - 4xx는 재시도 불가 (429 rate limit은 재시도해봐야 또 막힘)
 */
function isRetryable(status) {
  return status === 0 || (status >= 500 && status < 600);
}

/**
 * 지수 백오프로 재시도하는 HTTP 호출.
 */
function callWithRetry(method, url, body, params) {
  let attempt = 0;
  let res;
  while (attempt <= MAX_RETRIES) {
    res = method === 'POST' ? http.post(url, body, params) : http.get(url, params);
    if (!isRetryable(res.status)) {
      return res;
    }
    if (attempt < MAX_RETRIES) {
      // 100ms, 200ms, 400ms... 지수 백오프
      const waitMs = BACKOFF_BASE_MS * Math.pow(2, attempt);
      sleep(waitMs / 1000);
    }
    attempt += 1;
  }
  return res;
}

export default function () {
  const enterRes = callWithRetry(
    'POST',
    `${BASE}/api/queue/enter?concertId=${CID}`,
    null,
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(enterRes, { '대기열 진입 201': (r) => r.status === 201 });
  if (enterRes.status !== 201) {
    return;
  }

  const token = enterRes.json('data.token');
  for (let i = 0; i < MAX_STATUS_POLLS; i++) {
    const statusRes = callWithRetry(
      'GET',
      `${BASE}/api/queue/status?token=${token}&concertId=${CID}`,
      null,
      null,
    );
    check(statusRes, { '순번 조회 200': (r) => r.status === 200 });
    if (statusRes.status === 200 && statusRes.json('data.isAllowed')) {
      break;
    }
    sleep(POLL_SLEEP_SEC);
  }
}
