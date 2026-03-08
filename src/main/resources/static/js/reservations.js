const reservationListEl = document.getElementById('reservationList');
const holdListEl = document.getElementById('holdList');
const reservationStatusEl = document.getElementById('reservationStatus');
const reservationSummaryEl = document.getElementById('reservationSummary');
let currentTab = 'holds';

const formatNumber = (value) => {
	if (value === null || value === undefined) {
		return '-';
	}
	return Number(value).toLocaleString();
};

const formatDate = (value) => {
	const date = new Date(value);
	return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

const getStatusLabel = (status) => {
	switch (status) {
		case 'CONFIRMED':
			return '결제 완료';
		case 'CANCELED':
			return '취소';
		case 'REFUNDED':
			return '환불';
		default:
			return status || '-';
	}
};

const getStatusClass = (status) => {
	switch (status) {
		case 'CONFIRMED':
			return 'status-confirmed';
		case 'CANCELED':
		case 'REFUNDED':
			return 'status-canceled';
		default:
			return 'status-pending';
	}
};

const formatDday = (value) => {
	const date = new Date(value);
	if (Number.isNaN(date.getTime())) {
		return '';
	}
	const today = new Date();
	today.setHours(0, 0, 0, 0);
	const target = new Date(date);
	target.setHours(0, 0, 0, 0);
	const diffDays = Math.ceil((target - today) / 86400000);
	if (diffDays > 0) {
		return `D-${diffDays}`;
	}
	if (diffDays === 0) {
		return 'D-DAY';
	}
	return `D+${Math.abs(diffDays)}`;
};

const formatReservationId = (value) => {
	if (!value) {
		return '-';
	}
	return `#${String(value).padStart(6, '0')}`;
};

const buildReservationCard = (item) => {
	const seatSection = item.seatSection ?? item.section ?? '';
	const seatNo = item.seatNo ?? item.seatNumber ?? '';
	const seatInfo = seatSection || seatNo ? `${seatSection}구역 ${seatNo}번` : '좌석 정보 없음';
	const dateTime = formatDate(item.concertAt);
	const dday = formatDday(item.concertAt);
	return `
		<div class="reservation-card">
			<div class="reservation-main">
				<div>
					<h3>${item.concertTitle}</h3>
					<div class="meta">${item.venue}</div>
					<div class="meta">${dateTime}</div>
				</div>
				<div class="reservation-status ${getStatusClass(item.status)}">${getStatusLabel(item.status)}</div>
			</div>
			<div class="reservation-badges">
				<span class="badge light">예매번호 ${formatReservationId(item.reservationId)}</span>
				<span class="badge light">결제수단 카드</span>
				${dday ? `<span class="badge dday">${dday}</span>` : ''}
			</div>
			<div class="reservation-detail">
				<span class="label seat">
					<svg viewBox="0 0 24 24" aria-hidden="true">
						<path d="M7 10a3 3 0 0 1 6 0v3H7v-3Zm8 0a3 3 0 0 1 6 0v3h-6v-3Z"/>
						<path d="M3 13h18a1 1 0 0 1 1 1v3H2v-3a1 1 0 0 1 1-1Z"/>
					</svg>
					좌석
				</span>
				<strong class="value">${seatInfo}</strong>
				<span class="label price">
					<svg viewBox="0 0 24 24" aria-hidden="true">
						<path d="M12 3 2 7v6c0 5 4.5 7.5 10 9 5.5-1.5 10-4 10-9V7l-10-4Zm0 5a3 3 0 1 1 0 6 3 3 0 0 1 0-6Z"/>
					</svg>
					가격
				</span>
				<strong class="value">${formatNumber(item.seatPrice)}원</strong>
				<span class="label date">
					<svg viewBox="0 0 24 24" aria-hidden="true">
						<path d="M7 2h2v3H7V2Zm8 0h2v3h-2V2ZM4 5h16a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Zm0 6v9h16v-9H4Z"/>
					</svg>
					예약일
				</span>
				<strong class="value">${formatDate(item.reservedAt)}</strong>
			</div>
		</div>
	`;
};

const renderReservations = (items) => {
	if (!items.length) {
		reservationListEl.innerHTML = '<div class="status info">예매 내역이 없습니다.</div>';
		reservationSummaryEl.textContent = '0건';
		return;
	}
	reservationListEl.innerHTML = items.map(buildReservationCard).join('');
	reservationSummaryEl.textContent = `${items.length}건`;
};

const formatRemaining = (expiresAt) => {
	const end = new Date(expiresAt).getTime();
	const now = Date.now();
	const sec = Math.max(0, Math.floor((end - now) / 1000));
	if (sec < 60) return `${sec}초 남음`;
	const min = Math.floor(sec / 60);
	return `${min}분 남음`;
};

const buildHoldCard = (item) => {
	const dateTime = formatDate(item.concertAt);
	const seatInfo = `${item.section || ''}구역 ${item.seatNo || ''}번`.trim() || '좌석 정보 없음';
	const paymentUrl = `/payment.html?concertId=${item.concertId}&seatId=${item.seatId}&holdToken=${encodeURIComponent(item.holdToken)}`;
	return `
		<div class="reservation-card" data-hold-token="${item.holdToken}">
			<div class="reservation-main">
				<div>
					<h3>${item.concertTitle || '-'}</h3>
					<div class="meta">${item.venue || '-'}</div>
					<div class="meta">${dateTime}</div>
				</div>
				<div class="reservation-status status-pending">예약 중</div>
			</div>
			<div class="reservation-badges">
				<span class="badge light">${seatInfo}</span>
				<span class="badge light">${formatNumber(item.price)}원</span>
				<span class="badge dday">${formatRemaining(item.expiresAt)}</span>
			</div>
			<div class="hold-card-actions">
				<a class="primary" href="${paymentUrl}">결제하기</a>
				<button type="button" class="ghost hold-cancel-btn" data-hold-token="${item.holdToken}">홀드 취소</button>
			</div>
		</div>
	`;
};

const renderHolds = (items) => {
	if (!items.length) {
		holdListEl.innerHTML = '<div class="status info">예약 중인 좌석이 없습니다.</div>';
		return;
	}
	holdListEl.innerHTML = items.map(buildHoldCard).join('');
	holdListEl.querySelectorAll('.hold-cancel-btn').forEach((btn) => {
		btn.addEventListener('click', async () => {
			const token = btn.dataset.holdToken;
			if (!token || !confirm('이 좌석 예약을 취소하시겠습니까?')) return;
			try {
				const res = await fetch(`/api/holds/${encodeURIComponent(token)}`, { method: 'DELETE' });
				if (res.ok) loadHolds();
				else alert('취소에 실패했습니다.');
			} catch (e) {
				alert('취소에 실패했습니다.');
			}
		});
	});
};

const loadHolds = async () => {
	try {
		const result = await window.fetchJson('/api/holds/me');
		if (!result.ok) throw new Error('fetch failed');
		const items = Array.isArray(result.data) ? result.data : [];
		renderHolds(items);
	} catch (error) {
		holdListEl.innerHTML = '<div class="status error">예약 중 목록을 불러오지 못했습니다.</div>';
	}
};

const loadReservations = async () => {
	reservationStatusEl.textContent = '예매 내역을 불러오는 중...';
	try {
		const result = await window.fetchJson('/api/reservations/me');
		if (!result.ok) throw new Error('fetch failed');
		const items = Array.isArray(result.data) ? result.data : [];
		renderReservations(items);
		reservationStatusEl.textContent = '';
	} catch (error) {
		reservationStatusEl.textContent = '예매 내역을 불러오지 못했습니다.';
		reservationListEl.innerHTML = '';
		reservationSummaryEl.textContent = '-';
	}
};

document.querySelectorAll('.reservations-tab').forEach((tab) => {
	tab.addEventListener('click', () => {
		const tabName = tab.dataset.tab;
		currentTab = tabName;
		document.querySelectorAll('.reservations-tab').forEach((t) => t.classList.remove('active'));
		document.querySelectorAll('.reservations-tabpanel').forEach((p) => p.classList.remove('active'));
		tab.classList.add('active');
		document.getElementById(tabName === 'holds' ? 'holdsPanel' : 'reservationsPanel').classList.add('active');
		if (tabName === 'holds') loadHolds();
		else loadReservations();
	});
});

loadHolds();
loadReservations();
