// 결과 출력 영역 DOM 캐시
const concertsEl = document.getElementById('concerts');
const seatsEl = document.getElementById('seats');
const holdResultEl = document.getElementById('holdResult');
const reserveResultEl = document.getElementById('reserveResult');

// 콘서트 목록 조회 버튼 핸들러
document.getElementById('loadConcerts').addEventListener('click', async () => {
	concertsEl.textContent = 'Loading...';
	const res = await fetch('/api/concerts');
	const data = await res.json();
	concertsEl.textContent = JSON.stringify(data, null, 2);
});

// 좌석 목록 조회 버튼 핸들러
document.getElementById('loadSeats').addEventListener('click', async () => {
	const concertId = document.getElementById('concertId').value.trim();
	if (!concertId) {
		seatsEl.textContent = 'Provide concertId';
		return;
	}
	seatsEl.textContent = 'Loading...';
	const res = await fetch(`/api/concerts/${concertId}/seats`);
	const data = await res.json();
	seatsEl.textContent = JSON.stringify(data, null, 2);
});

// 좌석 홀드 요청 버튼 핸들러
document.getElementById('holdSeat').addEventListener('click', async () => {
	const userId = document.getElementById('userId').value.trim();
	const concertId = document.getElementById('concertId').value.trim();
	const seatId = document.getElementById('seatId').value.trim();
	if (!userId || !concertId || !seatId) {
		holdResultEl.textContent = 'Provide userId, concertId, seatId';
		return;
	}
	holdResultEl.textContent = 'Submitting...';
	const res = await fetch('/api/holds', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ userId, concertId: Number(concertId), seatId: Number(seatId) })
	});
	const data = await res.json();
	holdResultEl.textContent = JSON.stringify(data, null, 2);
});

// 예약 확정 버튼 핸들러
document.getElementById('reserveSeat').addEventListener('click', async () => {
	const userId = document.getElementById('userId').value.trim();
	const holdToken = document.getElementById('holdToken').value.trim();
	if (!userId || !holdToken) {
		reserveResultEl.textContent = 'Provide userId, holdToken';
		return;
	}
	reserveResultEl.textContent = 'Submitting...';
	const res = await fetch('/api/reservations', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ userId, holdToken })
	});
	const data = await res.json();
	reserveResultEl.textContent = JSON.stringify(data, null, 2);
});
