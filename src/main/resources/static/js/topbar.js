const initTopbar = () => {
	const userNameEl = document.getElementById('userName');
	const logoutBtn = document.getElementById('logoutBtn');

	const loadUser = async () => {
		if (!userNameEl) {
			return;
		}
		try {
			const result = await window.fetchJson('/api/auth/me');
			if (!result.ok) {
				throw new Error('unauthorized');
			}
			// 응답이 이제 { username, role } 객체
			const userData = result.data;
			userNameEl.textContent = userData.username;
		} catch (error) {
			userNameEl.textContent = 'user';
		}
	};

	const logout = async () => {
		const TOKEN_ACCESS = 'ticketing_accessToken';
		const TOKEN_REFRESH = 'ticketing_refreshToken';
		const headers = {};
		const access = sessionStorage.getItem(TOKEN_ACCESS);
		const refresh = sessionStorage.getItem(TOKEN_REFRESH);
		if (access) {
			headers.Authorization = `Bearer ${access}`;
		}
		if (refresh) {
			headers['X-Refresh-Token'] = refresh;
		}
		try {
			await window.apiFetch('/api/auth/logout', { method: 'POST', headers: new Headers(headers) });
		} finally {
			sessionStorage.removeItem(TOKEN_ACCESS);
			sessionStorage.removeItem(TOKEN_REFRESH);
			window.location.href = '/login.html?logout';
		}
	};

	if (logoutBtn) {
		logoutBtn.addEventListener('click', logout);
	}

	loadUser();
};

window.initTopbar = initTopbar;
