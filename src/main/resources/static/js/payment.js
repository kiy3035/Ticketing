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
		const [concertResult, seatResult] = await Promise.all([
			window.fetchJson('/api/concerts'),
			window.fetchJson(`/api/concerts/${concertId}/seats`)
		]);
		const concerts = Array.isArray(concertResult.data) ? concertResult.data : [];
		const seats = Array.isArray(seatResult.data) ? seatResult.data : [];
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
	setStatus('결제 요청 생성 중...');
	try {
		const requestResult = await window.fetchJson('/api/payments/request', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ holdToken })
		});
		if (!requestResult.ok) {
			throw new Error(requestResult.error?.message || '결제 요청 실패');
		}

		const paymentKey = requestResult.data.paymentKey;
		setStatus('결제 승인 중...');

		const approveResult = await window.fetchJson(`/api/payments/${paymentKey}/approve`, {
			method: 'POST'
		});
		if (!approveResult.ok) {
			throw new Error(approveResult.error?.message || '결제 승인 실패');
		}

		setStatus('결제 완료 처리 중...');
		const completeResult = await window.fetchJson(`/api/payments/${paymentKey}/complete`, {
			method: 'POST'
		});
		if (!completeResult.ok) {
			throw new Error(completeResult.error?.message || '결제 완료 실패');
		}

		setStatus(`결제 완료: 예약번호 ${completeResult.data.reservationId}`, 'ok');
		payBtn.disabled = true;
	} catch (error) {
		setStatus(`결제 실패: ${error.message}`, 'error');
	}
};

payBtn.addEventListener('click', submitPayment);

loadPaymentInfo();
