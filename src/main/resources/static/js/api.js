const fetchJson = async (url, options = {}) => {
	const res = await fetch(url, options);
	const contentType = res.headers.get('content-type') || '';
	let payload = null;
	if (contentType.includes('application/json')) {
		payload = await res.json();
	} else {
		payload = await res.text();
	}
	const unwrap = (data) =>
		data && typeof data === 'object' && 'data' in data ? data.data : data;
	const result = {
		ok: res.ok,
		status: res.status,
		data: unwrap(payload),
		error: payload
	};
	return result;
};

window.fetchJson = fetchJson;

/** API에서 오는 날짜/시간을 항상 한국 시간(DB·로컬과 동일)으로 표시 */
window.formatDateKorea = (value) => {
	const date = new Date(value);
	return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
};
