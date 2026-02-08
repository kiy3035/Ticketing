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
			
			// 역할에 따라 관리자 링크 표시 (나중에 추가 가능)
			if (userData.role === 'ADMIN') {
				// ADMIN 권한을 가진 사용자를 위한 추가 UI 처리
				console.log('Admin user logged in');
			}
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
