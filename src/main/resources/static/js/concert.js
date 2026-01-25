// 콘서트 상세 페이지에서 좌석과 예약 흐름을 처리한다.
const seatGrid = document.getElementById('seatGrid');
const holdTokenInput = document.getElementById('holdToken');
const reserveBtn = document.getElementById('reserveBtn');
const actionResult = document.getElementById('actionResult');
const concertTitle = document.getElementById('concertTitle');
const concertMeta = document.getElementById('concertMeta');
const statRemainingSeats = document.getElementById('statRemainingSeats');
const statAvgWait = document.getElementById('statAvgWait');
const statCurrentPrice = document.getElementById('statCurrentPrice');

const params = new URLSearchParams(window.location.search);
const concertId = params.get('concertId');
let seatById = {};
let selectedSeatId = null;

const renderSeats = (seats) => {
	if (!seats.length) {
		seatGrid.innerHTML = '<div class="status info">좌석 정보가 없습니다.</div>';
		return;
	}

	seatById = seats.reduce((acc, seat) => {
		acc[seat.id] = seat;
		return acc;
	}, {});

	const sections = Array.from(new Set(seats.map((seat) => seat.section))).sort();
	const rowsBySection = {};

	seats.forEach((seat) => {
		const parts = String(seat.seatNo).split('-');
		const seatNumber = Number(parts[1] || parts[0]);
		const row = Math.ceil(seatNumber / 10);
		const col = (seatNumber - 1) % 10;

		if (!rowsBySection[seat.section]) {
			rowsBySection[seat.section] = {};
		}
		if (!rowsBySection[seat.section][row]) {
			rowsBySection[seat.section][row] = Array(10).fill(null);
		}
		rowsBySection[seat.section][row][col] = seat;
	});

	const scaleRanges = {
		A: [0.92, 1.12],
		B: [0.90, 1.08],
		C: [0.88, 1.06],
		D: [0.86, 1.04]
	};

	const html = sections.map((section) => {
		const rows = rowsBySection[section] || {};
		const rowKeys = Object.keys(rows).map(Number).sort((a, b) => a - b);
		const [minScale, maxScale] = scaleRanges[section] || [0.9, 1.08];
		const maxRow = Math.max(...rowKeys, 10);

		const rowHtml = rowKeys.map((row) => {
			const seatsInRow = rows[row] || Array(10).fill(null);
			const scale = minScale + ((row - 1) / Math.max(1, maxRow - 1)) * (maxScale - minScale);
			const seatsHtml = seatsInRow.map((seat) => {
				if (!seat) {
					return '<span class="seat placeholder"></span>';
				}
				const tierClass = seat.section === 'A' || seat.price >= 150000 ? 'vip' : 'normal';
				const seatLabel = String(seat.seatNo).split('-')[1] || seat.seatNo;
				return `
					<button
						class="seat ${seat.status.toLowerCase()} ${tierClass}"
						data-seat-id="${seat.id}"
						title="${seat.section}-${seat.seatNo} (${seat.price}원)"
						${seat.status !== 'AVAILABLE' ? 'disabled' : ''}
					><span class="seat-number">${seatLabel}</span></button>
				`;
			});

			const leftSeats = seatsHtml.slice(0, 5).join('');
			const rightSeats = seatsHtml.slice(5).join('');

			return `
				<div class="seat-row" style="--row-scale:${scale.toFixed(2)}">
					<div class="seat-label">${section}${row}</div>
					<div class="seat-row-inner">
						<div class="seat-side">
							${leftSeats}
						</div>
						<span class="aisle-divider"></span>
						<div class="seat-side">
							${rightSeats}
						</div>
					</div>
				</div>
			`;
		}).join('');

		return `
			<div class="seat-section">
				<div class="seat-section-title">구역 ${section}</div>
				${rowHtml}
			</div>
		`;
	}).join('');

	seatGrid.innerHTML = html;

	const remaining = seats.filter((seat) => seat.status === 'AVAILABLE').length;
	statRemainingSeats.textContent = remaining.toLocaleString();
};

const loadConcertDetail = async () => {
	if (!concertId) {
		seatGrid.innerHTML = '<div class="status error">잘못된 접근입니다.</div>';
		return;
	}

	try {
		const concertResult = await window.fetchJson('/api/concerts');
		const concerts = Array.isArray(concertResult.data) ? concertResult.data : [];
		const concert = concerts.find((item) => String(item.id) === String(concertId));
		if (concert) {
			concertTitle.textContent = concert.title;
			concertMeta.textContent = `${concert.venue} | ${new Date(concert.startAt).toLocaleString()}`;
		}
	} catch (error) {
		// 상세 정보는 실패해도 좌석 조회로 진행한다.
	}

	try {
		const seatResult = await window.fetchJson(`/api/concerts/${concertId}/seats`);
		const seats = Array.isArray(seatResult.data) ? seatResult.data : [];
		renderSeats(seats);
	} catch (error) {
		seatGrid.innerHTML = '<div class="status error">좌석 정보를 불러오지 못했습니다.</div>';
	}
};

const holdSeat = async (seatId) => {
	const result = await window.fetchJson('/api/holds', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ concertId: Number(concertId), seatId: Number(seatId) })
	});
	if (!result.ok) {
		throw new Error(result.error?.message || '홀드 실패');
	}
	holdTokenInput.value = result.data.holdToken;
	return result.data;
};

const reserveSeat = async () => {
	if (!holdTokenInput.value) {
		actionResult.textContent = '홀드 토큰이 없습니다.';
		return;
	}
	actionResult.textContent = '예약 확정 중...';
	try {
		const result = await window.fetchJson('/api/reservations', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ holdToken: holdTokenInput.value })
		});
		if (!result.ok) {
			throw new Error(result.error?.message || '예약 실패');
		}
		actionResult.textContent = `예약 완료: 예약번호 ${result.data.reservationId}`;
		await loadConcertDetail();
	} catch (error) {
		actionResult.textContent = `예약 실패: ${error.message}`;
	}
};

const startPayment = () => {
	if (!selectedSeatId) {
		actionResult.textContent = '선택한 좌석 정보를 찾지 못했습니다.';
		return;
	}
	actionResult.textContent = '좌석을 홀드하는 중...';
	holdSeat(selectedSeatId)
		.then((data) => {
			actionResult.textContent = `홀드 성공: 만료 ${new Date(data.expiresAt).toLocaleString()}`;
			const query = new URLSearchParams({
				concertId,
				seatId: String(selectedSeatId),
				holdToken: data.holdToken
			});
			window.location.href = `/payment.html?${query.toString()}`;
		})
		.catch((error) => {
			actionResult.textContent = `홀드 실패: ${error.message}`;
		});
};

const loadAvgWait = async () => {
	try {
		const result = await window.fetchJson('/api/queue/count');
		const avgWaitMin = Math.max(1, Math.ceil(Number(result.data) / 50));
		statAvgWait.textContent = `${avgWaitMin}분`;
	} catch (error) {
		statAvgWait.textContent = '-';
	}
};

const updateCurrentPrice = (seatId) => {
	const seat = seatById[seatId];
	if (!seat) {
		statCurrentPrice.textContent = '-';
		return;
	}
	statCurrentPrice.textContent = `${seat.price.toLocaleString()}원`;
};

seatGrid.addEventListener('click', (event) => {
	const button = event.target.closest('button.seat');
	if (!button || button.disabled) {
		return;
	}
	selectedSeatId = button.dataset.seatId;
	updateCurrentPrice(button.dataset.seatId);
	actionResult.textContent = '좌석이 선택되었습니다. 예매하기를 눌러 홀드를 진행하세요.';
});

reserveBtn.addEventListener('click', startPayment);
loadConcertDetail();
loadAvgWait();
setInterval(loadAvgWait, 5000);