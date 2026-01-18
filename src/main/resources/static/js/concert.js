// 콘서트 상세 페이지에서 좌석과 예약 흐름을 처리한다.
const seatGrid = document.getElementById('seatGrid');
const holdTokenInput = document.getElementById('holdToken');
const reserveBtn = document.getElementById('reserveBtn');
const actionResult = document.getElementById('actionResult');
const concertTitle = document.getElementById('concertTitle');
const concertMeta = document.getElementById('concertMeta');
const userNameEl = document.getElementById('userName');
const logoutBtn = document.getElementById('logoutBtn');

const params = new URLSearchParams(window.location.search);
const concertId = params.get('concertId');

const renderSeats = (seats) => {
	if (!seats.length) {
		seatGrid.innerHTML = '<div class="status info">좌석 정보가 없습니다.</div>';
		return;
	}

	seatGrid.innerHTML = seats.map((seat) => `
		<button class="seat ${seat.status.toLowerCase()}" data-seat-id="${seat.id}" ${seat.status !== 'AVAILABLE' ? 'disabled' : ''}>
			<div>${seat.section}-${seat.seatNo}</div>
			<div>${seat.price}원</div>
			<div>${seat.status}</div>
		</button>
	`).join('');
};

const loadConcertDetail = async () => {
	if (!concertId) {
		seatGrid.innerHTML = '<div class="status error">잘못된 접근입니다.</div>';
		return;
	}

	try {
		const concertRes = await fetch('/api/concerts');
		const concerts = await concertRes.json();
		const concert = concerts.find((item) => String(item.id) === String(concertId));
		if (concert) {
			concertTitle.textContent = concert.title;
			concertMeta.textContent = `${concert.venue} | ${new Date(concert.startAt).toLocaleString()}`;
		}
	} catch (error) {
		// 상세 정보는 실패해도 좌석 조회로 진행한다.
	}

	try {
		const seatRes = await fetch(`/api/concerts/${concertId}/seats`);
		const seats = await seatRes.json();
		renderSeats(seats);
	} catch (error) {
		seatGrid.innerHTML = '<div class="status error">좌석 정보를 불러오지 못했습니다.</div>';
	}
};

const holdSeat = async (seatId) => {
	actionResult.textContent = '좌석을 홀드하는 중...';
	try {
		const res = await fetch('/api/holds', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ concertId: Number(concertId), seatId: Number(seatId) })
		});
		const data = await res.json();
		if (!res.ok) {
			throw new Error(data.message || '홀드 실패');
		}
		holdTokenInput.value = data.holdToken;
		actionResult.textContent = `홀드 성공: 만료 ${new Date(data.expiresAt).toLocaleString()}`;
		await loadConcertDetail();
	} catch (error) {
		actionResult.textContent = `홀드 실패: ${error.message}`;
	}
};

const reserveSeat = async () => {
	if (!holdTokenInput.value) {
		actionResult.textContent = '홀드 토큰이 없습니다.';
		return;
	}
	actionResult.textContent = '예약 확정 중...';
	try {
		const res = await fetch('/api/reservations', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ holdToken: holdTokenInput.value })
		});
		const data = await res.json();
		if (!res.ok) {
			throw new Error(data.message || '예약 실패');
		}
		actionResult.textContent = `예약 완료: 예약번호 ${data.reservationId}`;
		await loadConcertDetail();
	} catch (error) {
		actionResult.textContent = `예약 실패: ${error.message}`;
	}
};

const loadUser = async () => {
	try {
		const res = await fetch('/api/auth/me');
		const name = await res.text();
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

seatGrid.addEventListener('click', (event) => {
	const button = event.target.closest('button.seat');
	if (!button || button.disabled) {
		return;
	}
	holdSeat(button.dataset.seatId);
});

reserveBtn.addEventListener('click', reserveSeat);
logoutBtn.addEventListener('click', logout);

loadUser();
loadConcertDetail();
