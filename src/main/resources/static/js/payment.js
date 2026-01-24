const params = new URLSearchParams(window.location.search);
const concertId = params.get('concertId');
const seatId = params.get('seatId');
const holdToken = params.get('holdToken');

const payConcertTitle = document.getElementById('payConcertTitle');
const payConcertMeta = document.getElementById('payConcertMeta');
const paySeatInfo = document.getElementById('paySeatInfo');
const paySeatPrice = document.getElementById('paySeatPrice');
const payBtn = document.getElementById('payBtn');
const payResult = document.getElementById('payResult');
const userNameEl = document.getElementById('userName');
const logoutBtn = document.getElementById('logoutBtn');
const backToSeat = document.getElementById('backToSeat');

const setStatus = (message, status = 'info') => {
	payResult.textContent = message;
	payResult.className = `status ${status}`;
};

const setError = (message) => {
	setStatus(message, 'error');
	payBtn.disabled = true;
};

const loadPaymentInfo = async () => {
	if (!concertId || !seatId || !holdToken) {
		setError('결제 정보를 불러오지 못했습니다.');
		return;
	}
	if (backToSeat) {
		backToSeat.href = `/concert.html?concertId=${encodeURIComponent(concertId)}`;
	}

	try {
		const [concertRes, seatRes] = await Promise.all([
			fetch('/api/concerts'),
			fetch(`/api/concerts/${concertId}/seats`)
		]);
		const concerts = await concertRes.json();
		const seats = await seatRes.json();
		const concert = concerts.find((item) => String(item.id) === String(concertId));
		const seat = seats.find((item) => String(item.id) === String(seatId));

		if (concert) {
			payConcertTitle.textContent = concert.title;
			payConcertMeta.textContent = `${concert.venue} | ${new Date(concert.startAt).toLocaleString()}`;
		}
		if (seat) {
			paySeatInfo.textContent = `구역 ${seat.section} - ${seat.seatNo}`;
			paySeatPrice.textContent = `${seat.price.toLocaleString()}원`;
		} else {
			setError('좌석 정보를 찾지 못했습니다.');
		}
	} catch (error) {
		setError('결제 정보를 불러오지 못했습니다.');
	}
};

const submitPayment = async () => {
	if (!holdToken) {
		setError('홀드 토큰이 없습니다.');
		return;
	}
	setStatus('결제 처리 중...');
	try {
		const res = await fetch('/api/reservations', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ holdToken })
		});
		const data = await res.json();
		if (!res.ok) {
			throw new Error(data.message || '결제 실패');
		}
		setStatus(`결제 완료: 예약번호 ${data.reservationId}`, 'ok');
		payBtn.disabled = true;
	} catch (error) {
		setStatus(`결제 실패: ${error.message}`, 'error');
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

payBtn.addEventListener('click', submitPayment);
logoutBtn.addEventListener('click', logout);

loadUser();
loadPaymentInfo();
