/**
 * 결제 페이지 스크립트.
 * - 결제 수단: 포인트(POINT) / 카드(CARD, 토스페이먼츠 모의결제).
 * - 포인트: 요청 → 승인(본문 없음) → 완료.
 * - 카드: 요청 → 토스 결제창 열기 → successUrl 리다이렉트 시 paymentKey/orderId/amount 로 승인·완료.
 */
const params = new URLSearchParams(window.location.search);
const concertId = params.get('concertId');
const seatId = params.get('seatId');
const holdToken = params.get('holdToken');
/** 토스 successUrl 리다이렉트 시 쿼리로 전달되는 값들 */
const tossPaymentKey = params.get('paymentKey');
const tossOrderId = params.get('orderId');
const tossAmount = params.get('amount');
/** 우리 서버 Payment 의 paymentKey (successUrl 에 넣어두었다가 리다이렉트 후 승인 호출 시 사용) */
const ourPaymentKey = params.get('ourPaymentKey');
const isTossFail = params.get('fail');

const payConcertTitle = document.getElementById('payConcertTitle');
const payConcertMeta = document.getElementById('payConcertMeta');
const paySeatInfo = document.getElementById('paySeatInfo');
const paySeatPrice = document.getElementById('paySeatPrice');
const payBtn = document.getElementById('payBtn');
const payResult = document.getElementById('payResult');
const backToSeat = document.getElementById('backToSeat');
const payOverlay = document.getElementById('payOverlay');
const overlayTitle = document.getElementById('overlayTitle');
const overlayMessage = document.getElementById('overlayMessage');
const overlayHome = document.getElementById('overlayHome');

/** 라디오에서 선택된 결제 수단: POINT | CARD */
const getPaymentMethod = () => document.querySelector('input[name="paymentMethod"]:checked')?.value || 'POINT';

/** 토스페이먼츠 공식 SDK 동적 로드 (V2 standard). 이미 로드됐으면 즉시 resolve */
const TOSS_SDK_URL = 'https://js.tosspayments.com/v2/standard';
const loadTossScript = () => {
	if (typeof window.TossPayments === 'function') {
		return Promise.resolve();
	}
	return new Promise((resolve, reject) => {
		const script = document.createElement('script');
		script.src = TOSS_SDK_URL;
		script.async = true;
		script.onload = () => resolve();
		script.onerror = () => reject(new Error('토스 결제 스크립트를 불러오지 못했습니다. 네트워크 또는 차단 프로그램을 확인하세요.'));
		document.head.appendChild(script);
	});
};

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
			payConcertMeta.textContent = `${concert.venue} | ${(window.formatDateKorea || ((v) => new Date(v).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })))(concert.concertAt)}`;
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

/**
 * 결제 승인 후 완료까지 호출. 포인트는 approveBody 없음, 카드는 토스 paymentKey/orderId/amount 전달.
 * @param {string} paymentKey - 우리 서버 Payment 의 paymentKey
 * @param {object|null} approveBody - 카드일 때 { paymentKey, orderId, amount } (토스 리다이렉트 쿼리 값)
 */
const runApproveAndComplete = async (paymentKey, approveBody = null) => {
	setStatus('결제 승인 중...');
	const approveResult = await window.fetchJson(`/api/payments/${paymentKey}/approve`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: approveBody ? JSON.stringify(approveBody) : '{}'
	});
	if (!approveResult.ok) {
		throw new Error(approveResult.error?.message || approveResult.error?.code || '결제 승인 실패');
	}
	setStatus('결제 완료 처리 중...');
	const completeResult = await window.fetchJson(`/api/payments/${paymentKey}/complete`, {
		method: 'POST'
	});
	if (!completeResult.ok) {
		throw new Error(completeResult.error?.message || '결제 완료 실패');
	}
	return completeResult;
};

const showSuccessOverlay = (reservationId) => {
	setStatus(`결제 완료: 예약번호 ${reservationId}`, 'ok');
	if (payBtn) payBtn.disabled = true;
	if (payOverlay) {
		overlayTitle.textContent = '결제가 완료되었습니다';
		overlayMessage.textContent = `예약번호 ${reservationId}로 정상 처리되었습니다. 곧 홈으로 이동합니다.`;
		payOverlay.classList.remove('hidden');
		payOverlay.setAttribute('aria-hidden', 'false');
		setTimeout(() => { window.location.href = '/app.html'; }, 3000);
	}
};

/** 결제하기 버튼: 수단별로 요청 후 포인트면 승인·완료, 카드면 토스 결제창 → 리다이렉트 후 handleTossReturn 에서 승인·완료 */
const submitPayment = async () => {
	if (!holdToken) {
		setError('홀드 토큰이 없습니다.');
		return;
	}
	const paymentMethod = getPaymentMethod();
	setStatus('결제 요청 생성 중...');
	try {
		const requestResult = await window.fetchJson('/api/payments/request', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ holdToken, paymentMethod })
		});
		if (!requestResult.ok) {
			throw new Error(requestResult.error?.message || '결제 요청 실패');
		}

		const paymentKey = requestResult.data.paymentKey;
		const orderId = requestResult.data.orderId;
		const amount = requestResult.data.amount;

		// 카드: 토스 결제창 띄우기. 스크립트 동적 로드 후 requestPayment 호출
		if (paymentMethod === 'CARD') {
			const keyRes = await window.fetchJson('/api/payments/toss-client-key');
			const clientKey = (keyRes.data && keyRes.data.clientKey) ? keyRes.data.clientKey : (keyRes.clientKey || '');
			if (!clientKey || (!clientKey.startsWith('test_') && !clientKey.startsWith('live_'))) {
				throw new Error('토스페이먼츠 클라이언트 키가 설정되지 않았습니다. (.env의 TOSS_CLIENT_KEY 확인)');
			}
			await loadTossScript();
			const baseUrl = window.location.origin + window.location.pathname;
			const successUrl = baseUrl + '?' + new URLSearchParams({
				concertId: concertId || '',
				seatId: seatId || '',
				holdToken: holdToken || '',
				ourPaymentKey: paymentKey
			}).toString();
			const failUrl = baseUrl + '?' + new URLSearchParams({
				concertId: concertId || '',
				seatId: seatId || '',
				holdToken: holdToken || '',
				fail: '1'
			}).toString();
			// 공식 V2: TossPayments(clientKey) → payment({ customerKey }) → requestPayment(...)
			const tossPayments = window.TossPayments(clientKey);
			const payment = tossPayments.payment({ customerKey: 'anonymous_' + Date.now() });
			await payment.requestPayment({
				method: 'CARD',
				amount: { currency: 'KRW', value: amount },
				orderId: orderId,
				orderName: '콘서트 예매',
				successUrl: successUrl,
				failUrl: failUrl
			});
			return;
		}

		// 포인트: 승인(본문 없음) → 완료
		const completeResult = await runApproveAndComplete(paymentKey);
		showSuccessOverlay(completeResult.data.reservationId);
	} catch (error) {
		setStatus(`결제 실패: ${error.message}`, 'error');
	}
};

payBtn.addEventListener('click', submitPayment);

if (overlayHome) {
	overlayHome.addEventListener('click', () => { window.location.href = '/app.html'; });
}

/** 페이지 로드 시: 토스 successUrl 리다이렉트로 돌아온 경우 승인·완료 호출. fail 이면 에러 메시지만 표시 */
const handleTossReturn = async () => {
	if (isTossFail) {
		setStatus('카드 결제가 취소되었거나 실패했습니다.', 'error');
		window.history.replaceState({}, '', window.location.pathname + '?' + new URLSearchParams({ concertId: concertId || '', seatId: seatId || '', holdToken: holdToken || '' }).toString());
		return;
	}
	if (tossPaymentKey && tossOrderId && tossAmount && ourPaymentKey) {
		payBtn.disabled = true;
		setStatus('결제 승인 처리 중...');
		try {
			const completeResult = await runApproveAndComplete(ourPaymentKey, {
				paymentKey: tossPaymentKey,
				orderId: tossOrderId,
				amount: Number(tossAmount)
			});
			showSuccessOverlay(completeResult.data.reservationId);
			window.history.replaceState({}, '', window.location.pathname + '?' + new URLSearchParams({ concertId: concertId || '', seatId: seatId || '', holdToken: holdToken || '' }).toString());
		} catch (error) {
			setStatus(`결제 승인 실패: ${error.message}`, 'error');
			payBtn.disabled = false;
		}
		return true;
	}
	return false;
};

(async () => {
	await loadPaymentInfo();
	const handled = await handleTossReturn();
	if (!handled) {
		// 일반 로드 시 추가 처리 없음
	}
})();
