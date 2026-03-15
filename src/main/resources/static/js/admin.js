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

// 상단 통계 카드(총 사용자·예약·결제 등)는 페이지 로드 시 1회 로드
const loadStatistics = async () => {
	try {
		const userStats = await window.fetchJson('/api/admin/statistics/users');
		if (userStats.ok) {
			document.getElementById('statTotalUsers').textContent = userStats.data.total || 0;
		}
		const reservationStats = await window.fetchJson('/api/admin/statistics/reservations');
		if (reservationStats.ok) {
			document.getElementById('statTotalReservations').textContent = reservationStats.data.total || 0;
		}
		const paymentStats = await window.fetchJson('/api/admin/statistics/payments');
		if (paymentStats.ok) {
			document.getElementById('statTodayPayments').textContent = paymentStats.data.today || 0;
			document.getElementById('statTotalRevenuePoint').textContent =
				`${(paymentStats.data.totalRevenuePoint || 0).toLocaleString()}포인트`;
			document.getElementById('statTotalRevenueCard').textContent =
				`${(paymentStats.data.totalRevenueCard || 0).toLocaleString()}원`;
		}
	} catch (error) {
		console.error('Statistics load failed:', error);
	}
};
document.addEventListener('DOMContentLoaded', () => {
	loadStatistics();
	loadTabData('unsoldSeats'); // 첫 탭(마감 후 미판매 좌석) 데이터 로드
});

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
		case 'unsoldSeats':
			loadUnsoldSeats();
			break;
		case 'payments':
			loadPayments();
			break;
		case 'users':
			loadUsers();
			break;
	}
};

/**
 * 마감 후 미판매 좌석 통계 로드
 */
const loadUnsoldSeats = async () => {
	const tbody = document.getElementById('unsoldTableBody');
	const fromInput = document.getElementById('unsoldFrom');
	const toInput = document.getElementById('unsoldTo');
	try {
		let url = '/api/admin/statistics/unsold-seats?page=0&size=100';
		if (fromInput && fromInput.value) url += `&from=${fromInput.value}`;
		if (toInput && toInput.value) url += `&to=${toInput.value}`;
		const result = await window.fetchJson(url);
		// Spring Page: content 배열은 result.content 또는 result.data?.content
		const page = result.data != null ? result.data : result;
		const list = page.content || [];
		const fmt = window.formatDateKorea || ((v) => new Date(v).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }));
		if (!list.length) {
			tbody.innerHTML = '<tr><td colspan="6" class="no-data">해당 조건의 마감 공연이 없습니다.</td></tr>';
			return;
		}
		tbody.innerHTML = list.map((row) => `
			<tr>
				<td>${row.title ?? '-'}</td>
				<td>${row.venue ?? '-'}</td>
				<td>${fmt(row.concertAt)}</td>
				<td>${(row.totalSeats ?? 0).toLocaleString()}</td>
				<td>${(row.soldSeats ?? 0).toLocaleString()}</td>
				<td>${(row.unsoldSeats ?? 0).toLocaleString()}</td>
			</tr>
		`).join('');
	} catch (error) {
		console.error('Unsold seats load failed:', error);
		tbody.innerHTML = '<tr><td colspan="6" class="no-data">데이터를 불러오지 못했습니다.</td></tr>';
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
			tbody.innerHTML = '<tr><td colspan="6" class="no-data">결제 내역이 없습니다.</td></tr>';
			return;
		}

		const methodLabel = (m) => (m === 'CARD' ? '카드(토스)' : '포인트');
		const amountText = (p) => p.paymentMethod === 'CARD'
			? `${(p.amount || 0).toLocaleString()}원`
			: `${(p.amount || 0).toLocaleString()}포인트`;

		const html = result.data.map((payment) => `
			<tr>
				<td>${payment.paymentKey}</td>
				<td>${payment.username}</td>
				<td>${methodLabel(payment.paymentMethod)}</td>
				<td>${amountText(payment)}</td>
				<td>
					<span class="badge" style="background: ${getStatusColor(payment.status)}">${payment.status}</span>
				</td>
				<td>${(window.formatDateKorea || ((v) => new Date(v).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })))(payment.completedAt)}</td>
			</tr>
		`).join('');

		tbody.innerHTML = html;
	} catch (error) {
		console.error('Payments load failed:', error);
		document.getElementById('paymentTableBody').innerHTML = 
			'<tr><td colspan="6" class="no-data">데이터를 불러오지 못했습니다.</td></tr>';
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
				<td>${(window.formatDateKorea || ((v) => new Date(v).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })))(user.createdAt)}</td>
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

const unsoldSearchBtn = document.getElementById('unsoldSearchBtn');
if (unsoldSearchBtn) {
	unsoldSearchBtn.addEventListener('click', () => loadUnsoldSeats());
}

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

// 페이지 로드 시 초기 통계 데이터 로드
loadStatistics();
