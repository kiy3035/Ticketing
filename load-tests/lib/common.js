import http from 'k6/http';

export function baseUrl() {
  return __ENV.BASE_URL || 'http://localhost:8080';
}

export function concertId() {
  return __ENV.CONCERT_ID || '1';
}

let accessToken = '';
let refreshToken = '';

/**
 * JWT 로그인 (POST /api/auth/login). 이후 http 요청에 authHeaders() 사용.
 * @returns {boolean}
 */
export function jwtLogin(url, username, password) {
  if (!username || !password) {
    return false;
  }
  const res = http.post(`${url}/api/auth/login`, JSON.stringify({ username, password }), {
    headers: { 'Content-Type': 'application/json' },
  });
  if (res.status !== 200) {
    return false;
  }
  accessToken = res.json('data.accessToken') || res.json('accessToken');
  refreshToken = res.json('data.refreshToken') || res.json('refreshToken');
  return !!(accessToken && refreshToken);
}

/** @param {boolean} [withJsonContentType] JSON 본문 POST 시 true */
export function authHeaders(withJsonContentType) {
  const h = {
    Authorization: `Bearer ${accessToken}`,
    'X-Refresh-Token': refreshToken,
  };
  if (withJsonContentType) {
    h['Content-Type'] = 'application/json';
  }
  return h;
}

/** @deprecated jwtLogin 사용 */
export function formLogin(url, username, password) {
  return jwtLogin(url, username, password);
}
