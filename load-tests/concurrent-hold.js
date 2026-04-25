/**
 * 좌석 동시 선점 검증 (concurrent hold correctness test)
 *
 * 목적: N명이 동일 좌석에 동시에 홀드를 시도할 때 정확히 1명만 성공하는지 검증.
 *       Redis 분산 락(SETNX) + Lua 원자 연산이 실제 부하에서 동작함을 증명한다.
 *
 * 기대 결과:
 *   - 201 Created     : 정확히 1건 (한 명만 선점 성공)
 *   - 409 Conflict    : N-1건 수준 (이미 선점됨 — Lua EXISTS 반환)
 *   - 429 Too Many Req: 0~소수건 (락 경합 실패 — 정상 동작)
 *   - 5xx             : 0건 (서버 에러 없어야)
 *
 * 실행 전 준비:
 *   1. 테스트용 콘서트와 좌석이 DB에 있어야 한다.
 *   2. K6_HOT_SEAT_ID: 모든 VU가 동시에 시도할 단일 좌석 ID
 *      (1000개 중 1개를 고정해야 실질적인 경합이 발생한다)
 *   3. TEST_USER / TEST_PASS: 인증 가능한 계정 1개 (JWT는 무상태 → 여러 VU 공유 가능)
 *   4. 실행 전 해당 좌석의 홀드가 없는 상태여야 한다 (Redis hold:seat:{id} 키 없어야)
 *
 * 실행 예시:
 *   k6 run \
 *     -e BASE_URL=http://<app>:8080 \
 *     -e CONCERT_ID=<concertId> \
 *     -e K6_HOT_SEAT_ID=<seatId> \
 *     -e K6_PEAK_VU=100 \
 *     -e TEST_USER=<username> \
 *     -e TEST_PASS=<password> \
 *     load-tests/concurrent-hold.js
 *
 * 결과 해석:
 *   - checks{check:201 선점 성공}의 passes 값이 1이면 분산 락 정상 동작
 *   - passes > 1이면 중복 선점 발생 → 동시성 버그
 *   - 5xx가 있으면 서버 오류 조사 필요
 */
import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, concertId } from './lib/common.js';

const BASE = baseUrl();
const CID  = concertId();
const PEAK_VU    = parseInt(__ENV.K6_PEAK_VU    || '100');
const HOT_SEAT_ID = parseInt(__ENV.K6_HOT_SEAT_ID || '0');
const TEST_USER  = __ENV.TEST_USER || '';
const TEST_PASS  = __ENV.TEST_PASS || '';

export const options = {
  // shared-iterations: PEAK_VU개의 iteration을 PEAK_VU개의 VU가 최대한 동시에 처리
  // → 가능한 한 동시에 같은 좌석에 요청이 몰리도록 유도
  scenarios: {
    concurrent_burst: {
      executor: 'shared-iterations',
      vus: PEAK_VU,
      iterations: PEAK_VU,
      maxDuration: '60s',
    },
  },
  thresholds: {
    // http_req_failed는 사용 안 함: 이 테스트는 409/429가 99%여야 정상이므로 비2xx 비율로 판단 불가
    'checks{check:5xx 없음}': ['rate>0.99'],               // 서버 에러 없어야
    'checks{check:201 선점 성공}': ['rate<=0.02'],          // 성공률 ≤ 2% (100명 중 1~2건만 성공해야)
  },
};

/**
 * setup: 1회 로그인 후 토큰을 모든 VU에 공유.
 * JWT는 무상태(stateless) → 여러 VU가 같은 토큰으로 동시 읽기해도 안전하다.
 */
export function setup() {
  if (!TEST_USER || !TEST_PASS) {
    throw new Error('TEST_USER, TEST_PASS 환경변수를 설정하세요.');
  }
  if (!HOT_SEAT_ID || HOT_SEAT_ID <= 0) {
    throw new Error('K6_HOT_SEAT_ID 환경변수를 설정하세요. (예: -e K6_HOT_SEAT_ID=42)');
  }

  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ username: TEST_USER, password: TEST_PASS }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (res.status !== 200) {
    throw new Error(`로그인 실패: status=${res.status}`);
  }

  const accessToken  = res.json('data.accessToken');
  const refreshToken = res.json('data.refreshToken');

  if (!accessToken) {
    throw new Error('accessToken 발급 실패 — 응답 구조 확인 필요');
  }

  console.log(`로그인 성공. VU ${PEAK_VU}개, 대상 좌석 ID: ${HOT_SEAT_ID}`);
  return { accessToken, refreshToken };
}

export default function ({ accessToken, refreshToken }) {
  const headers = {
    Authorization: `Bearer ${accessToken}`,
    'X-Refresh-Token': refreshToken,
    'Content-Type': 'application/json',
  };

  const res = http.post(
    `${BASE}/api/holds`,
    JSON.stringify({ concertId: Number(CID), seatId: Number(HOT_SEAT_ID) }),
    { headers },
  );

  // 각 응답을 분류해 분포 확인
  check(res, {
    '5xx 없음'    : (r) => r.status < 500,
    '201 선점 성공': (r) => r.status === 201,
    '409 이미 선점': (r) => r.status === 409,
    '429 락 경합' : (r) => r.status === 429,
  });
}
