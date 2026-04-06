/**
 * JWT 기능 시나리오 점검 (부하 테스트가 아닌 기능 검증용).
 *
 * 포함 시나리오:
 * - 정상 동작
 * - Access 만료 + Refresh 유효 (Case 2)
 * - Refresh 만료 + Access 유효 (Case 3)
 * - Access/Refresh 둘 다 만료 (Case 1)
 * - 로그아웃 후 블랙리스트 차단 + TTL 만료 후 해제 확인
 * - 토큰 탈취(회전된 구 Refresh 재사용) 대응
 * - 추가: 헤더 누락, 서명 위조
 *
 * 실행 예:
 * k6 run -e BASE_URL=http://localhost:18080 -e TEST_USER=user -e TEST_PASS=pass load-tests/jwt-scenarios.js
 *
 * 만료 케이스 빠른 검증 권장:
 * - 테스트 환경에서 access TTL / refresh TTL 을 짧게(예: 30s / 90s) 설정
 * - 또는 K6_WAIT_EXPIRY_MAX_SEC 값을 늘려 실제 만료까지 대기
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import encoding from 'k6/encoding';
import { Counter, Rate } from 'k6/metrics';
import { baseUrl } from './lib/common.js';

export const options = {
  scenarios: {
    jwt_cases: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: __ENV.K6_MAX_DURATION || '20m',
    },
  },
  thresholds: {
    jwt_case_pass_rate: ['rate>0.99'],
  },
};

const BASE = baseUrl();
const TEST_USER = __ENV.TEST_USER || '';
const TEST_PASS = __ENV.TEST_PASS || '';
const TEST_USER_2 = __ENV.TEST_USER_2 || ''; // subject mismatch/도난 재현용 (선택)
const TEST_PASS_2 = __ENV.TEST_PASS_2 || '';
const WAIT_MAX = Math.max(0, parseInt(__ENV.K6_WAIT_EXPIRY_MAX_SEC || '120', 10) || 120);
const PROTECTED_PATH = __ENV.JWT_PROTECTED_PATH || '/api/concerts/counts';

const resultCount = new Counter('jwt_case_total');
const resultPass = new Rate('jwt_case_pass_rate');

function jsonField(res, a, b) {
  const va = a ? res.json(a) : undefined;
  if (va !== undefined && va !== null) return va;
  return b ? res.json(b) : undefined;
}

function login(username, password) {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username, password }), {
    headers: { 'Content-Type': 'application/json' },
  });
  if (res.status !== 200) return null;
  const access = jsonField(res, 'data.accessToken', 'accessToken');
  const refresh = jsonField(res, 'data.refreshToken', 'refreshToken');
  if (!access || !refresh) return null;
  return { access, refresh };
}

function authHeaders(access, refresh, withJson) {
  const h = {
    Authorization: `Bearer ${access}`,
    'X-Refresh-Token': refresh,
  };
  if (withJson) h['Content-Type'] = 'application/json';
  return h;
}

function callProtected(access, refresh) {
  return http.get(`${BASE}${PROTECTED_PATH}`, {
    headers: authHeaders(access, refresh, false),
  });
}

function callProtectedMissingRefresh(access) {
  return http.get(`${BASE}${PROTECTED_PATH}`, {
    headers: { Authorization: `Bearer ${access}` },
  });
}

function logout(access, refresh) {
  return http.post(`${BASE}/api/auth/logout`, null, {
    headers: authHeaders(access, refresh, false),
  });
}

function decodePayload(token) {
  try {
    const parts = String(token).split('.');
    if (parts.length < 2) return null;
    const b64url = parts[1];
    let b64 = b64url.replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4 !== 0) b64 += '=';
    const s = encoding.b64decode(b64, 'std', 's');
    return JSON.parse(s);
  } catch (e) {
    return null;
  }
}

function waitUntilExpired(token, label) {
  const p = decodePayload(token);
  if (!p || !p.exp) {
    return { waited: false, ready: false, reason: `${label}: exp 없음` };
  }
  const nowSec = Math.floor(Date.now() / 1000);
  const remain = Number(p.exp) - nowSec;
  if (remain <= 0) {
    return { waited: false, ready: true, reason: `${label}: 이미 만료` };
  }
  const waitSec = remain + 1;
  if (waitSec > WAIT_MAX) {
    return { waited: false, ready: false, reason: `${label}: 만료까지 ${waitSec}s > WAIT_MAX(${WAIT_MAX})` };
  }
  sleep(waitSec);
  return { waited: true, ready: true, reason: `${label}: ${waitSec}s 대기 후 만료` };
}

function tamperSignature(token) {
  const parts = String(token).split('.');
  if (parts.length !== 3) return token;
  const sig = parts[2];
  const flipped = sig.length > 3 ? `${sig.slice(0, -2)}xx` : `${sig}xx`;
  return `${parts[0]}.${parts[1]}.${flipped}`;
}

function mark(name, ok, detail) {
  resultCount.add(1, { case: name });
  resultPass.add(ok, { case: name });
  // eslint-disable-next-line no-console
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${name} - ${detail}`);
}

export default function () {
  if (!TEST_USER || !TEST_PASS) {
    mark('INIT', false, 'TEST_USER/TEST_PASS 미지정');
    return;
  }

  // S1: 정상 동작
  const s1 = login(TEST_USER, TEST_PASS);
  if (!s1) {
    mark('S1_NORMAL', false, '로그인 실패');
    return;
  }
  const s1Res = callProtected(s1.access, s1.refresh);
  const s1Ok = check(s1Res, { 'S1 200': (r) => r.status === 200 });
  mark('S1_NORMAL', s1Ok, `status=${s1Res.status}`);

  // E5: 헤더 누락
  const e5Res = callProtectedMissingRefresh(s1.access);
  const e5Ok = check(e5Res, { 'E5 401': (r) => r.status === 401 });
  mark('E5_MISSING_HEADER', e5Ok, `status=${e5Res.status}`);

  // E2: 서명 위조
  const badAccess = tamperSignature(s1.access);
  const e2Res = callProtected(badAccess, s1.refresh);
  const e2Ok = check(e2Res, { 'E2 401': (r) => r.status === 401 });
  mark('E2_TAMPERED_SIGNATURE', e2Ok, `status=${e2Res.status}`);

  // S2: Access 만료 + Refresh 유효 => Access/Refresh 재발급 헤더
  const s2 = login(TEST_USER, TEST_PASS);
  if (s2) {
    const w = waitUntilExpired(s2.access, 'S2 access');
    if (!w.ready) {
      mark('S2_ACCESS_EXPIRED', false, `스킵: ${w.reason}`);
    } else {
      const r = callProtected(s2.access, s2.refresh);
      const newA = r.headers['X-New-Access-Token'];
      const newR = r.headers['X-New-Refresh-Token'];
      const ok = r.status === 200 && !!newA && !!newR;
      mark('S2_ACCESS_EXPIRED', ok, `status=${r.status}, newA=${!!newA}, newR=${!!newR}`);

      // S6: 회전된 구 Refresh 재사용(탈취 가정) => family 전체 폐기 401
      if (ok) {
        const replay = callProtected(s2.access, s2.refresh);
        const replayOk = replay.status === 401;
        mark('S6_STOLEN_REFRESH_REPLAY', replayOk, `status=${replay.status}`);

        // 참고: 정상 클라이언트(새 토큰)도 family 폐기로 실패 가능
        const after = callProtected(newA, newR);
        const afterOk = after.status === 401;
        mark('S6_FAMILY_REVOKED_EFFECT', afterOk, `status=${after.status}`);
      }
    }
  } else {
    mark('S2_ACCESS_EXPIRED', false, '로그인 실패');
  }

  // S3: Refresh 만료 + Access 유효 => X-New-Refresh-Token
  const s3 = login(TEST_USER, TEST_PASS);
  if (s3) {
    const w = waitUntilExpired(s3.refresh, 'S3 refresh');
    if (!w.ready) {
      mark('S3_REFRESH_EXPIRED', false, `스킵: ${w.reason}`);
    } else {
      const r = callProtected(s3.access, s3.refresh);
      const newR = r.headers['X-New-Refresh-Token'];
      const ok = r.status === 200 && !!newR;
      mark('S3_REFRESH_EXPIRED', ok, `status=${r.status}, newR=${!!newR}`);
    }
  } else {
    mark('S3_REFRESH_EXPIRED', false, '로그인 실패');
  }

  // S4: Access+Refresh 둘 다 만료 => 401
  const s4 = login(TEST_USER, TEST_PASS);
  if (s4) {
    const wa = waitUntilExpired(s4.access, 'S4 access');
    const wr = waitUntilExpired(s4.refresh, 'S4 refresh');
    if (!wa.ready || !wr.ready) {
      mark('S4_BOTH_EXPIRED', false, `스킵: ${wa.reason} / ${wr.reason}`);
    } else {
      const r = callProtected(s4.access, s4.refresh);
      const ok = r.status === 401;
      mark('S4_BOTH_EXPIRED', ok, `status=${r.status}`);
    }
  } else {
    mark('S4_BOTH_EXPIRED', false, '로그인 실패');
  }

  // S5: 블랙리스트 TTL - 로그아웃 직후는 401, TTL 경과 후 해제 확인
  const s5 = login(TEST_USER, TEST_PASS);
  if (s5) {
    logout(s5.access, s5.refresh);
    const blocked = callProtected(s5.access, s5.refresh);
    const blockedOk = blocked.status === 401;
    mark('S5_BLACKLIST_IMMEDIATE', blockedOk, `status=${blocked.status}`);

    const wait = waitUntilExpired(s5.access, 'S5 access ttl');
    if (!wait.ready) {
      mark('S5_BLACKLIST_TTL_RELEASE', false, `스킵: ${wait.reason}`);
    } else {
      const after = callProtected(s5.access, s5.refresh);
      const released = after.status !== 401; // 블랙리스트 원인만 제거됐는지 관찰
      mark('S5_BLACKLIST_TTL_RELEASE', released, `status=${after.status}`);
    }
  } else {
    mark('S5_BLACKLIST_IMMEDIATE', false, '로그인 실패');
  }

  // 추가: subject mismatch (가능할 때만)
  if (TEST_USER_2 && TEST_PASS_2) {
    const a = login(TEST_USER, TEST_PASS);
    const b = login(TEST_USER_2, TEST_PASS_2);
    if (a && b) {
      const mismatch = callProtected(a.access, b.refresh);
      const ok = mismatch.status === 401;
      mark('E4_SUBJECT_MISMATCH', ok, `status=${mismatch.status}`);
    } else {
      mark('E4_SUBJECT_MISMATCH', false, '2계정 로그인 실패');
    }
  }
}
