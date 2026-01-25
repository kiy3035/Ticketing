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
