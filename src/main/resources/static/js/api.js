const fetchJson = async (url, options = {}) => {
	const res = await fetch(url, options);
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
	return result;
};

window.fetchJson = fetchJson;

/** API에서 오는 날짜/시간을 항상 한국 시간(DB·로컬과 동일)으로 표시 */
window.formatDateKorea = (value) => {
	const date = new Date(value);
	return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
};
