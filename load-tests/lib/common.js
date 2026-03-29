import http from 'k6/http';

// [조정] 앱 URL·공연 ID는 보통 실행 시 -e BASE_URL / -e CONCERT_ID 로 넘기면 파일 수정 없이 바꿀 수 있음
export function baseUrl() {
  return __ENV.BASE_URL || 'http://localhost:8080';
}

export function concertId() {
  return __ENV.CONCERT_ID || '1';
}

/**
 * 폼 로그인. 성공 시 302 + Set-Cookie. k6 VU 단위 쿠키 저장소에 유지된다.
 * @returns {boolean}
 */
export function formLogin(url, username, password) {
  if (!username || !password) {
    return false;
  }
  const body = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;
  const res = http.post(`${url}/login`, body, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    redirects: 0,
  });
  return res.status === 302 || (res.status >= 200 && res.status < 400);
}
