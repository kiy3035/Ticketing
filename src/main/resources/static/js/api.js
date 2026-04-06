const TOKEN_ACCESS = 'ticketing_accessToken';
const TOKEN_REFRESH = 'ticketing_refreshToken';

const applyAuthHeaders = (headers) => {
	const h = new Headers(headers || {});
	const access = sessionStorage.getItem(TOKEN_ACCESS);
	const refresh = sessionStorage.getItem(TOKEN_REFRESH);
	if (access) {
		h.set('Authorization', `Bearer ${access}`);
	}
	if (refresh) {
		h.set('X-Refresh-Token', refresh);
	}
	return h;
};

const storeNewTokens = (res) => {
	const na = res.headers.get('X-New-Access-Token');
	const nr = res.headers.get('X-New-Refresh-Token');
	if (na) {
		sessionStorage.setItem(TOKEN_ACCESS, na);
	}
	if (nr) {
		sessionStorage.setItem(TOKEN_REFRESH, nr);
	}
};

/** JWT가 필요한 API 호출용 (정적 페이지에서 공통 사용) */
const apiFetch = async (url, options = {}) => {
	const opts = { ...options, headers: applyAuthHeaders(options.headers) };
	const res = await fetch(url, opts);
	storeNewTokens(res);
	return res;
};

window.apiFetch = apiFetch;
window.ticketingTokenKeys = { TOKEN_ACCESS, TOKEN_REFRESH };

const fetchJson = async (url, options = {}) => {
	const res = await apiFetch(url, options);
	const contentType = res.headers.get('content-type') || '';
	const rawText = await res.text();
	let payload = null;
	const trimmed = rawText.trim();
	if (trimmed.length > 0 && contentType.includes('application/json')) {
		try {
			payload = JSON.parse(rawText);
		} catch {
			payload = { message: rawText };
		}
	} else if (trimmed.length > 0) {
		payload = rawText;
	}
	const unwrap = (data) =>
		data && typeof data === 'object' && 'data' in data ? data.data : data;
	const errorObj =
		payload && typeof payload === 'object'
			? payload
			: trimmed.length > 0
				? { message: typeof payload === 'string' ? payload : rawText }
				: null;
	const result = {
		ok: res.ok,
		status: res.status,
		data: unwrap(payload),
		error: errorObj
	};
	if (res.status === 401 && url.includes('/api/') && !url.includes('/api/auth/login')) {
		sessionStorage.removeItem(TOKEN_ACCESS);
		sessionStorage.removeItem(TOKEN_REFRESH);
		const path = window.location.pathname || '';
		if (path !== '/login.html' && path !== '/signup.html') {
			window.location.href = '/login.html';
		}
	}
	return result;
};

window.fetchJson = fetchJson;

window.formatDateKorea = (value) => {
	const date = new Date(value);
	return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
};
