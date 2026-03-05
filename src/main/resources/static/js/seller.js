/**
 * 판매자 대시보드: 내 공연 목록, 공연 등록, 좌석/예약/매출 조회
 */

let currentConcertId = null;

const validateSellerRole = async () => {
	try {
		const result = await window.fetchJson('/api/auth/me');
		if (!result.ok || result.data.role !== 'SELLER') {
			window.location.href = '/app.html';
			return;
		}
	} catch (e) {
		window.location.href = '/login.html';
	}
};
validateSellerRole();

function statusColor(status) {
	const map = { UPCOMING: 'rgba(148, 163, 184, 0.15)', ONGOING: 'rgba(34, 197, 94, 0.15)', COMPLETED: 'rgba(59, 130, 246, 0.15)', CANCELLED: 'rgba(239, 68, 68, 0.15)' };
	return map[status] || 'rgba(148, 163, 184, 0.15)';
}

function formatDate(iso) {
	if (!iso) return '-';
	return new Date(iso).toLocaleString('ko-KR');
}

async function loadConcerts() {
	const tbody = document.getElementById('concertTableBody');
	try {
		const result = await window.fetchJson('/api/seller/concerts');
		if (!result.ok || !result.data || result.data.length === 0) {
			tbody.innerHTML = '<tr><td colspan="8" class="no-data">등록한 공연이 없습니다. 공연 등록 버튼으로 추가하세요.</td></tr>';
			return;
		}
		const rows = result.data.map(c => `
			<tr>
				<td>${c.title}</td>
				<td>${c.venue}</td>
				<td>${formatDate(c.startAt)}</td>
				<td><span class="badge" style="background:${statusColor(c.status)}">${c.status}</span></td>
				<td>${c.seatCount}</td>
				<td>${c.reservedCount}</td>
				<td>${(c.totalRevenue || 0).toLocaleString()}P</td>
				<td>
					<button type="button" class="primary btn-sm" data-action="detail" data-id="${c.id}">상세</button>
					${c.status !== 'CANCELLED' ? `<button type="button" class="ghost btn-sm" data-action="cancel" data-id="${c.id}">취소</button>` : ''}
				</td>
			</tr>
		`).join('');
		tbody.innerHTML = rows;
		tbody.querySelectorAll('[data-action="detail"]').forEach(btn => {
			btn.addEventListener('click', () => showDetail(Number(btn.dataset.id)));
		});
		tbody.querySelectorAll('[data-action="cancel"]').forEach(btn => {
			btn.addEventListener('click', () => cancelConcert(Number(btn.dataset.id)));
		});
	} catch (e) {
		tbody.innerHTML = '<tr><td colspan="8" class="no-data">목록을 불러오지 못했습니다.</td></tr>';
	}
}

function showDetail(concertId) {
	currentConcertId = concertId;
	const panel = document.getElementById('concertDetail');
	panel.classList.add('visible');
	loadDetailMeta(concertId);
	loadSeats(concertId);
	loadReservations(concertId);
	loadSales(concertId);
	document.querySelectorAll('.seller-tab').forEach(t => t.classList.remove('active'));
	document.querySelectorAll('.seller-tabpanel').forEach(p => p.classList.remove('active'));
	document.querySelector('.seller-tab[data-tab="seats"]').classList.add('active');
	document.getElementById('seats').classList.add('active');
}

async function loadDetailMeta(concertId) {
	const result = await window.fetchJson(`/api/seller/concerts/${concertId}`);
	if (!result.ok) return;
	const c = result.data;
	document.getElementById('detailTitle').textContent = c.title;
	document.getElementById('detailMeta').innerHTML = `
		장소: ${c.venue} · ${formatDate(c.startAt)} ~ ${formatDate(c.endAt)} ·
		<span class="badge" style="background:${statusColor(c.status)}">${c.status}</span>
		· 좌석 ${c.seatCount} · 예매 ${c.reservedCount} · 매출 ${(c.totalRevenue || 0).toLocaleString()}P
	`;
}

async function loadSeats(concertId) {
	const result = await window.fetchJson(`/api/seller/concerts/${concertId}/seats`);
	const el = document.getElementById('seatsContent');
	if (!result.ok || !result.data || result.data.length === 0) {
		el.innerHTML = '<p class="no-data">등록된 좌석이 없습니다. 좌석 일괄 등록으로 추가하세요.</p>';
		return;
	}
	el.innerHTML = `
		<table class="seller-table">
			<thead><tr><th>구역</th><th>좌석번호</th><th>가격</th><th>상태</th></tr></thead>
			<tbody>
				${result.data.map(s => `
					<tr>
						<td>${s.section}</td>
						<td>${s.seatNo}</td>
						<td>${(s.price || 0).toLocaleString()}P</td>
						<td><span class="badge" style="background:${statusColor(s.status)}">${s.status}</span></td>
					</tr>
				`).join('')}
			</tbody>
		</table>
	`;
}

async function loadReservations(concertId) {
	const result = await window.fetchJson(`/api/seller/concerts/${concertId}/reservations`);
	const el = document.getElementById('reservationsContent');
	if (!result.ok || !result.data || result.data.length === 0) {
		el.innerHTML = '<p class="no-data">예약이 없습니다.</p>';
		return;
	}
	el.innerHTML = `
		<table class="seller-table">
			<thead><tr><th>사용자</th><th>구역</th><th>좌석</th><th>가격</th><th>상태</th><th>예약 시각</th></tr></thead>
			<tbody>
				${result.data.map(r => `
					<tr>
						<td>${r.userId}</td>
						<td>${r.section}</td>
						<td>${r.seatNo}</td>
						<td>${(r.price || 0).toLocaleString()}P</td>
						<td>${r.status}</td>
						<td>${formatDate(r.reservedAt)}</td>
					</tr>
				`).join('')}
			</tbody>
		</table>
	`;
}

async function loadSales(concertId) {
	const result = await window.fetchJson(`/api/seller/concerts/${concertId}/sales`);
	const el = document.getElementById('salesContent');
	if (!result.ok) {
		el.innerHTML = '<p class="no-data">매출 정보를 불러오지 못했습니다.</p>';
		return;
	}
	const d = result.data;
	el.innerHTML = `
		<p><strong>총 매출:</strong> ${(d.totalRevenue || 0).toLocaleString()}P (${d.totalCount || 0}건)</p>
		<table class="seller-table">
			<thead><tr><th>결제키</th><th>사용자</th><th>금액</th><th>완료 시각</th></tr></thead>
			<tbody>
				${(d.payments || []).length === 0
					? '<tr><td colspan="4" class="no-data">결제 내역이 없습니다.</td></tr>'
					: (d.payments || []).map(p => `
						<tr>
							<td>${p.paymentKey}</td>
							<td>${p.userId}</td>
							<td>${(p.amount || 0).toLocaleString()}P</td>
							<td>${formatDate(p.completedAt)}</td>
						</tr>
					`).join('')}
			</tbody>
		</table>
	`;
}

async function cancelConcert(concertId) {
	if (!confirm('이 공연을 취소하시겠습니까? 취소 시 환불 배치가 실행됩니다.')) return;
	try {
		const res = await fetch(`/api/seller/concerts/${concertId}/cancel`, { method: 'POST' });
		if (!res.ok) throw new Error(res.statusText);
		document.getElementById('concertDetail').classList.remove('visible');
		currentConcertId = null;
		loadConcerts();
	} catch (e) {
		alert('취소 처리에 실패했습니다.');
	}
}

function toISOInstant(localStr) {
	if (!localStr) return null;
	const d = new Date(localStr);
	return d.toISOString();
}

document.getElementById('btnCreateConcert').addEventListener('click', () => {
	document.getElementById('createTitle').value = '';
	document.getElementById('createVenue').value = '';
	document.getElementById('createStartAt').value = '';
	document.getElementById('createEndAt').value = '';
	document.getElementById('modalCreateConcert').classList.add('visible');
});

document.getElementById('modalCreateCancel').addEventListener('click', () => {
	document.getElementById('modalCreateConcert').classList.remove('visible');
});

document.getElementById('modalCreateSubmit').addEventListener('click', async () => {
	const title = document.getElementById('createTitle').value.trim();
	const venue = document.getElementById('createVenue').value.trim();
	const startAt = toISOInstant(document.getElementById('createStartAt').value);
	const endAt = toISOInstant(document.getElementById('createEndAt').value);
	const category = document.getElementById('createCategory').value;
	if (!title || !venue || !startAt || !endAt) {
		alert('제목, 장소, 시작/종료 일시를 모두 입력하세요.');
		return;
	}
	try {
		const res = await fetch('/api/seller/concerts', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ title, venue, startAt, endAt, category })
		});
		const data = await res.json();
		if (!res.ok) throw new Error(data.message || res.statusText);
		document.getElementById('modalCreateConcert').classList.remove('visible');
		loadConcerts();
	} catch (e) {
		alert('등록 실패: ' + (e.message || e));
	}
});

document.getElementById('btnCloseDetail').addEventListener('click', () => {
	document.getElementById('concertDetail').classList.remove('visible');
	currentConcertId = null;
});

document.querySelectorAll('.seller-tab').forEach(tab => {
	tab.addEventListener('click', () => {
		const tabName = tab.dataset.tab;
		document.querySelectorAll('.seller-tab').forEach(t => t.classList.remove('active'));
		document.querySelectorAll('.seller-tabpanel').forEach(p => p.classList.remove('active'));
		tab.classList.add('active');
		document.getElementById(tabName).classList.add('active');
	});
});

document.getElementById('btnAddSeats').addEventListener('click', () => {
	if (!currentConcertId) return;
	document.getElementById('seatSection').value = 'A';
	document.getElementById('seatNoFrom').value = '1';
	document.getElementById('seatNoTo').value = '10';
	document.getElementById('seatPrice').value = '50000';
	document.getElementById('modalAddSeats').classList.add('visible');
});

document.getElementById('modalSeatsCancel').addEventListener('click', () => {
	document.getElementById('modalAddSeats').classList.remove('visible');
});

document.getElementById('modalSeatsSubmit').addEventListener('click', async () => {
	if (!currentConcertId) return;
	const section = document.getElementById('seatSection').value.trim();
	const seatNoFrom = parseInt(document.getElementById('seatNoFrom').value, 10);
	const seatNoTo = parseInt(document.getElementById('seatNoTo').value, 10);
	const price = parseInt(document.getElementById('seatPrice').value, 10);
	if (!section || isNaN(seatNoFrom) || isNaN(seatNoTo) || seatNoFrom > seatNoTo || price < 0) {
		alert('구역, 좌석 번호 범위, 가격을 올바르게 입력하세요.');
		return;
	}
	try {
		const res = await fetch(`/api/seller/concerts/${currentConcertId}/seats`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ section, seatNoFrom, seatNoTo, price })
		});
		const data = await res.json();
		if (!res.ok) throw new Error(data.message || res.statusText);
		document.getElementById('modalAddSeats').classList.remove('visible');
		loadSeats(currentConcertId);
		loadDetailMeta(currentConcertId);
		loadConcerts();
	} catch (e) {
		alert('좌석 등록 실패: ' + (e.message || e));
	}
});

loadConcerts();
