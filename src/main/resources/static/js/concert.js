// 콘서트 상세 페이지에서 좌석과 예약 흐름을 처리한다.
const seatGrid = document.getElementById('seatGrid');
const holdTokenInput = document.getElementById('holdToken');
const reserveBtn = document.getElementById('reserveBtn');
const actionResult = document.getElementById('actionResult');
const concertTitle = document.getElementById('concertTitle');
const concertMeta = document.getElementById('concertMeta');

const params = new URLSearchParams(window.location.search);
const concertId = params.get('concertId');
const queueToken = params.get('queueToken');
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

	const html = sections.map((section) => {
		const rows = rowsBySection[section] || {};
		const rowKeys = Object.keys(rows).map(Number).sort((a, b) => a - b);

		const rowHtml = rowKeys.map((row) => {
			const seatsInRow = rows[row] || Array(10).fill(null);
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
				<div class="seat-row">
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
};

// 대기열 입장 허용 여부 확인 (패턴 B: required=false면 토큰 없이도 진입 가능)
const checkQueueAccess = async () => {
	if (!concertId) {
		return false;
	}

	// URL에 queueToken이 있으면 입장 허용 확인
	if (queueToken) {
		try {
			const result = await window.fetchJson(`/api/queue/allowed?token=${queueToken}`);
			if (result.ok && result.data.allowed && result.data.concertId && String(result.data.concertId) === String(concertId)) {
				return true; // 입장 허용됨
			}
		} catch (error) {
			console.error('대기열 입장 허용 확인 실패:', error);
		}
	}

	// 토큰 없음: 대기열 필요 여부 확인. required=false면 바로 진입 허용
	try {
		const result = await window.fetchJson(`/api/queue/required?concertId=${concertId}`);
		if (result.ok && result.data && !result.data.required) {
			return true; // 대기열 불필요 시 바로 좌석 페이지 허용
		}
	} catch (error) {
		console.error('대기열 필요 여부 확인 실패:', error);
	}

	// 대기열 필요하거나 확인 실패 시 대기열로 리다이렉트
	window.location.href = `/queue.html?concertId=${concertId}`;
	return false;
};

const loadConcertDetail = async () => {
	if (!concertId) {
		seatGrid.innerHTML = '<div class="status error">잘못된 접근입니다.</div>';
		return;
	}

	// 대기열 접근 확인
	const hasAccess = await checkQueueAccess();
	if (!hasAccess) {
		return; // 대기열로 리다이렉트됨
	}

	try {
		const concertResult = await window.fetchJson('/api/concerts');
		const concerts = Array.isArray(concertResult.data) ? concertResult.data : [];
		const concert = concerts.find((item) => String(item.id) === String(concertId));
		if (concert) {
			concertTitle.textContent = concert.title;
			concertMeta.textContent = `${concert.venue} | ${(window.formatDateKorea || ((v) => new Date(v).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })))(concert.concertAt)}`;
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

// 예약 확정은 결제 완료(POST /api/payments/{paymentKey}/complete) 시에만 이루어짐. 별도 예약 확정 API 없음.
const reserveSeat = async () => {
	if (!holdTokenInput.value) {
		actionResult.textContent = '홀드 토큰이 없습니다.';
		return;
	}
	actionResult.textContent = '예약은 결제 완료 시 자동으로 확정됩니다. 아래 "결제하기"를 이용해 주세요.';
};

const startPayment = () => {
	if (!selectedSeatId) {
		actionResult.textContent = '선택한 좌석 정보를 찾지 못했습니다.';
		return;
	}
	// 연타 방지: 클릭 후 버튼 비활성화, 실패 시에만 다시 활성화
	reserveBtn.disabled = true;
	actionResult.textContent = '좌석을 홀드하는 중...';
	holdSeat(selectedSeatId)
		.then((data) => {
			actionResult.textContent = `홀드 성공: 만료 ${(window.formatDateKorea || ((v) => new Date(v).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })))(data.expiresAt)}`;
			const query = new URLSearchParams({
				concertId,
				seatId: String(selectedSeatId),
				holdToken: data.holdToken
			});
			window.location.href = `/payment.html?${query.toString()}`;
		})
		.catch((error) => {
			actionResult.textContent = `홀드 실패: ${error.message}`;
			reserveBtn.disabled = false;
		});
};

const loadAvgWait = async () => {
	if (!concertId) {
		statAvgWait.textContent = '-';
		return;
	}
	try {
		const result = await window.fetchJson(`/api/queue/count?concertId=${concertId}`);
		const totalWaiting = Number(result.data) || 0;
		const avgWaitMin = Math.max(1, Math.ceil(totalWaiting / 50));
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

// Collapsible 토글 기능
const seatToggle = document.getElementById('seatToggle');
const seatContent = document.getElementById('seatContent');

if (seatToggle && seatContent) {
	seatToggle.addEventListener('click', () => {
		seatToggle.classList.toggle('open');
		seatContent.classList.toggle('open');
	});

	// 초기 상태: 열려있음
	seatToggle.classList.add('open');
	seatContent.classList.add('open');
}

seatGrid.addEventListener('click', (event) => {
	const button = event.target.closest('button.seat');
	if (!button || button.disabled) {
		return;
	}
	// 좌석 선택 시 이전 선택 해제 및 현재 선택 강조
	const prev = seatGrid.querySelector('button.seat.selected');
	if (prev && prev !== button) {
		prev.classList.remove('selected');
	}
	button.classList.add('selected');
	selectedSeatId = button.dataset.seatId;
	updateCurrentPrice(button.dataset.seatId);
	actionResult.textContent = '좌석이 선택되었습니다. 예매하기를 눌러 홀드를 진행하세요.';
});

reserveBtn.addEventListener('click', startPayment);
loadConcertDetail();