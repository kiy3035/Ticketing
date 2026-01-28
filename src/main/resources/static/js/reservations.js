const reservationListEl = document.getElementById('reservationList');
const reservationStatusEl = document.getElementById('reservationStatus');
const reservationSummaryEl = document.getElementById('reservationSummary');

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
	const period = `${formatDate(item.startAt)} ~ ${formatDate(item.endAt)}`;
	const dday = formatDday(item.startAt);
	return `
		<div class="reservation-card">
			<div class="reservation-main">
				<div>
					<h3>${item.concertTitle}</h3>
					<div class="meta">${item.venue}</div>
					<div class="meta">${period}</div>
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

const loadReservations = async () => {
	reservationStatusEl.textContent = '예매 내역을 불러오는 중...';
	try {
		const result = await window.fetchJson('/api/reservations/me');
		if (!result.ok) {
			throw new Error('fetch failed');
		}
		const items = Array.isArray(result.data) ? result.data : [];
		renderReservations(items);
		reservationStatusEl.textContent = '';
	} catch (error) {
		reservationStatusEl.textContent = '예매 내역을 불러오지 못했습니다.';
		reservationListEl.innerHTML = '';
		reservationSummaryEl.textContent = '-';
	}
};

loadReservations();
