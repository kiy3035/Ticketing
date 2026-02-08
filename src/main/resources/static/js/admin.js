/**
 * 관리자 대시보드 JavaScript
 * 
 * 탭 전환, 데이터 조회, 검색 기능을 처리합니다.
 */

// ADMIN 권한 검증 (ADMIN이 아니면 /app.html로 리다이렉트)
const validateAdminRole = async () => {
	try {
		const result = await window.fetchJson('/api/auth/me');
		if (!result.ok || result.data.role !== 'ADMIN') {
			// ADMIN이 아니면 일반 사용자 페이지로 리다이렉트
			window.location.href = '/app.html';
		}
	} catch (error) {
		// 인증 실패 시 로그인 페이지로 리다이렉트
		window.location.href = '/login.html';
	}
};

// 페이지 로드 시 권한 검증
validateAdminRole();

// 탭 전환 기능
const tabButtons = document.querySelectorAll('.admin-tab');
const tabPanels = document.querySelectorAll('.admin-tabpanel');

tabButtons.forEach((button) => {
	button.addEventListener('click', () => {
		const tabName = button.dataset.tab;

		// 모든 탭과 패널 비활성화
		tabButtons.forEach((btn) => btn.classList.remove('active'));
		tabPanels.forEach((panel) => panel.classList.remove('active'));

		// 클릭한 탭 활성화
		button.classList.add('active');
		document.getElementById(tabName).classList.add('active');

		// 탭 변경 시 데이터 새로 로드
		loadTabData(tabName);
	});
});

// 탭별 데이터 로드
const loadTabData = async (tabName) => {
	switch (tabName) {
		case 'statistics':
			loadStatistics();
			break;
		case 'payments':
			loadPayments();
			break;
		case 'users':
			loadUsers();
			break;
		case 'concerts':
			loadConcerts();
			break;
	}
};

/**
 * 통계 데이터 로드
 */
const loadStatistics = async () => {
	try {
		// 사용자 통계
		const userStats = await window.fetchJson('/api/admin/statistics/users');
		if (userStats.ok) {
			document.getElementById('statTotalUsers').textContent = userStats.data.total || 0;
		}

		// 예약 통계
		const reservationStats = await window.fetchJson('/api/admin/statistics/reservations');
		if (reservationStats.ok) {
			document.getElementById('statTotalReservations').textContent = reservationStats.data.total || 0;
		}

		// 결제 통계
		const paymentStats = await window.fetchJson('/api/admin/statistics/payments');
		if (paymentStats.ok) {
			document.getElementById('statTodayPayments').textContent = paymentStats.data.today || 0;
			document.getElementById('statTotalRevenue').textContent = 
				`${(paymentStats.data.totalRevenue || 0).toLocaleString()}포인트`;
		}
	} catch (error) {
		console.error('Statistics load failed:', error);
	}
};

/**
 * 결제 데이터 로드
 */
const loadPayments = async (searchQuery = '') => {
	try {
		const url = searchQuery 
			? `/api/admin/payments?search=${encodeURIComponent(searchQuery)}`
			: '/api/admin/payments';
		
		const result = await window.fetchJson(url);
		const tbody = document.getElementById('paymentTableBody');

		if (!result.ok || !result.data || result.data.length === 0) {
			tbody.innerHTML = '<tr><td colspan="5" class="no-data">결제 내역이 없습니다.</td></tr>';
			return;
		}

		const html = result.data.map((payment) => `
			<tr>
				<td>${payment.paymentKey}</td>
				<td>${payment.username}</td>
				<td>${payment.amount.toLocaleString()}포인트</td>
				<td>
					<span class="badge" style="background: ${getStatusColor(payment.status)}">${payment.status}</span>
				</td>
				<td>${new Date(payment.completedAt).toLocaleString()}</td>
			</tr>
		`).join('');

		tbody.innerHTML = html;
	} catch (error) {
		console.error('Payments load failed:', error);
		document.getElementById('paymentTableBody').innerHTML = 
			'<tr><td colspan="5" class="no-data">데이터를 불러오지 못했습니다.</td></tr>';
	}
};

/**
 * 사용자 데이터 로드
 */
const loadUsers = async (searchQuery = '') => {
	try {
		const url = searchQuery
			? `/api/admin/users?search=${encodeURIComponent(searchQuery)}`
			: '/api/admin/users';

		const result = await window.fetchJson(url);
		const tbody = document.getElementById('userTableBody');

		if (!result.ok || !result.data || result.data.length === 0) {
			tbody.innerHTML = '<tr><td colspan="5" class="no-data">사용자가 없습니다.</td></tr>';
			return;
		}

		const html = result.data.map((user) => `
			<tr>
				<td>${user.username}</td>
				<td>${user.email}</td>
				<td>${(user.point || 0).toLocaleString()}포인트</td>
				<td>
					<span class="badge" style="background: ${user.role === 'ADMIN' ? 'rgba(249, 115, 22, 0.15)' : 'rgba(34, 197, 94, 0.15)'}">${user.role}</span>
				</td>
				<td>${new Date(user.createdAt).toLocaleString()}</td>
			</tr>
		`).join('');

		tbody.innerHTML = html;
	} catch (error) {
		console.error('Users load failed:', error);
		document.getElementById('userTableBody').innerHTML = 
			'<tr><td colspan="5" class="no-data">데이터를 불러오지 못했습니다.</td></tr>';
	}
};

/**
 * 공연 데이터 로드
 */
const loadConcerts = async (searchQuery = '') => {
	try {
		const url = searchQuery
			? `/api/concerts?query=${encodeURIComponent(searchQuery)}`
			: '/api/concerts';

		const result = await window.fetchJson(url);
		const tbody = document.getElementById('concertTableBody');

		if (!result.ok || !result.data || result.data.length === 0) {
			tbody.innerHTML = '<tr><td colspan="5" class="no-data">공연이 없습니다.</td></tr>';
			return;
		}

		// 각 공연의 좌석 수를 조회하기 위해 Promise.all 사용 (필요시)
		const html = result.data.map((concert) => `
			<tr>
				<td>${concert.title}</td>
				<td>${concert.venue}</td>
				<td>${new Date(concert.startAt).toLocaleString()}</td>
				<td>
					<span class="badge" style="background: ${getStatusColor(concert.status)}">${concert.status}</span>
				</td>
				<td>-</td>
			</tr>
		`).join('');

		tbody.innerHTML = html;
	} catch (error) {
		console.error('Concerts load failed:', error);
		document.getElementById('concertTableBody').innerHTML = 
			'<tr><td colspan="5" class="no-data">데이터를 불러오지 못했습니다.</td></tr>';
	}
};

/**
 * 상태별 색상 반환
 */
const getStatusColor = (status) => {
	const statusColors = {
		'READY': 'rgba(148, 163, 184, 0.15)',
		'APPROVED': 'rgba(245, 158, 11, 0.15)',
		'COMPLETED': 'rgba(34, 197, 94, 0.15)',
		'CANCELED': 'rgba(239, 68, 68, 0.15)',
		'PENDING': 'rgba(148, 163, 184, 0.15)',
		'CONFIRMED': 'rgba(34, 197, 94, 0.15)',
	};
	return statusColors[status] || 'rgba(148, 163, 184, 0.15)';
};

// 검색 버튼 이벤트 리스너
document.getElementById('paymentSearchBtn').addEventListener('click', () => {
	const query = document.getElementById('paymentSearchInput').value;
	loadPayments(query);
});

document.getElementById('userSearchBtn').addEventListener('click', () => {
	const query = document.getElementById('userSearchInput').value;
	loadUsers(query);
});

document.getElementById('concertSearchBtn').addEventListener('click', () => {
	const query = document.getElementById('concertSearchInput').value;
	loadConcerts(query);
});

// Enter 키 검색 지원
document.getElementById('paymentSearchInput').addEventListener('keypress', (e) => {
	if (e.key === 'Enter') {
		document.getElementById('paymentSearchBtn').click();
	}
});

document.getElementById('userSearchInput').addEventListener('keypress', (e) => {
	if (e.key === 'Enter') {
		document.getElementById('userSearchBtn').click();
	}
});

document.getElementById('concertSearchInput').addEventListener('keypress', (e) => {
	if (e.key === 'Enter') {
		document.getElementById('concertSearchBtn').click();
	}
});

// 페이지 로드 시 초기 통계 데이터 로드
loadStatistics();
