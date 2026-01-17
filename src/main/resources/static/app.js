const concertsEl = document.getElementById('concerts');
const seatsEl = document.getElementById('seats');
const holdResultEl = document.getElementById('holdResult');
const reserveResultEl = document.getElementById('reserveResult');

document.getElementById('loadConcerts').addEventListener('click', async () => {
	concertsEl.textContent = 'Loading...';
	const res = await fetch('/api/concerts');
	const data = await res.json();
	concertsEl.textContent = JSON.stringify(data, null, 2);
});

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
