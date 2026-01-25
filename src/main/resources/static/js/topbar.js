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
			const name = result.data;
			userNameEl.textContent = name;
		} catch (error) {
			userNameEl.textContent = 'user';
		}
	};

	const logout = async () => {
		try {
			await fetch('/logout', { method: 'POST' });
		} finally {
			window.location.href = '/login.html?logout';
		}
	};

	if (logoutBtn) {
		logoutBtn.addEventListener('click', logout);
	}

	loadUser();
};

window.initTopbar = initTopbar;
