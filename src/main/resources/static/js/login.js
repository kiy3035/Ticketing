const params = new URLSearchParams(window.location.search);
const statusOk = document.getElementById('statusOk');
const statusError = document.getElementById('statusError');
const statusLogout = document.getElementById('statusLogout');

if (params.has('signup')) {
	statusOk.hidden = false;
}
if (params.has('error')) {
	statusError.hidden = false;
}
if (params.has('logout')) {
	statusLogout.hidden = false;
}

const TOKEN_ACCESS = 'ticketing_accessToken';
const TOKEN_REFRESH = 'ticketing_refreshToken';

const loginForm = document.getElementById('loginForm');
if (loginForm) {
	loginForm.addEventListener('submit', async (e) => {
		e.preventDefault();
		statusError.hidden = true;
		const username = document.getElementById('username').value.trim();
		const password = document.getElementById('password').value;
		try {
			const res = await fetch('/api/auth/login', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ username, password })
			});
			const raw = await res.text();
			let payload = null;
			if (raw.trim()) {
				try {
					payload = JSON.parse(raw);
				} catch {
					payload = null;
				}
			}
			if (!res.ok) {
				statusError.hidden = false;
				return;
			}
			const data = payload && payload.data !== undefined ? payload.data : payload;
			if (!data || !data.accessToken || !data.refreshToken) {
				statusError.hidden = false;
				return;
			}
			sessionStorage.setItem(TOKEN_ACCESS, data.accessToken);
			sessionStorage.setItem(TOKEN_REFRESH, data.refreshToken);
			window.location.href = '/app.html';
		} catch {
			statusError.hidden = false;
		}
	});
}
