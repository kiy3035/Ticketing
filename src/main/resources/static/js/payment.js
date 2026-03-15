/**
 * 결제 페이지 스크립트 (주문서형 위젯 연동)
 *
 * [흐름 요약]
 * - POINT: POST /request → POST /approve (body 없음) → POST /complete → 완료 오버레이
 * - CARD:  POST /request(orderId 확보) → widgets.requestPayment → 토스 리다이렉트
 *          → successUrl 복귀 시 쿼리(paymentKey, orderId, amount, ourPaymentKey)로
 *            POST /approve(body에 토스 값) → POST /complete → 완료 오버레이
 *
 * [키 제한] 주문서형은 결제위젯 연동 키(test_gck_/test_gsk_)만 사용. test_ck_ 사용 시 에러 메시지 표시.
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
const tossWidgetWrap = document.getElementById('toss-widget-wrap');

/** 좌석 금액(원). loadPaymentInfo 에서 설정. 주문서형 위젯 setAmount 에 사용 */
let paymentAmount = 0;
/** 토스 주문서형 위젯 인스턴스. 카드 선택 후 한 번만 초기화 */
let tossWidgets = null;

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

/**
 * 주문서형: 토스 위젯 초기화(결제 수단 + 이용약관). 카드 선택 시 1회만 수행.
 * test_ 키가 아니면 초기화하지 않고 에러 메시지 반환.
 */
const initTossWidgetIfNeeded = async () => {
	if (tossWidgets) return;
	const keyRes = await window.fetchJson('/api/payments/toss-client-key');
	const clientKey = (keyRes.data && keyRes.data.clientKey) ? keyRes.data.clientKey : (keyRes.clientKey || '');
	if (!clientKey || !clientKey.startsWith('test_')) {
		setStatus('모의결제는 .env에 TOSS_CLIENT_KEY=test_gck_... 로 설정하세요.', 'error');
		throw new Error('Invalid or missing test client key');
	}
	// 주문서형(위젯)은 결제위젯 연동 키(test_gck_)만 지원. API 개별 연동 키(test_ck_)는 위젯 미지원
	if (clientKey.includes('_ck_')) {
		setStatus('주문서형(위젯)은 "결제위젯 연동 키"(test_gck_...)가 필요합니다. 개발자센터에서 위젯용 클라이언트 키·시크릿(test_gsk_...) 발급 후 .env에 TOSS_CLIENT_KEY, TOSS_SECRET_KEY 로 넣어주세요.', 'error');
		throw new Error('Widget requires test_gck_ client key');
	}
	await loadTossScript();
	const tossPayments = window.TossPayments(clientKey);
	tossWidgets = tossPayments.widgets({ customerKey: window.TossPayments.ANONYMOUS });
	await tossWidgets.setAmount({ currency: 'KRW', value: paymentAmount });
	await Promise.all([
		tossWidgets.renderPaymentMethods({ selector: '#payment-method', variantKey: 'DEFAULT' }),
		tossWidgets.renderAgreement({ selector: '#agreement', variantKey: 'AGREEMENT' })
	]);
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
			paymentAmount = Number(seat.price) || 0;
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

/**
 * 결제하기 버튼 클릭 시
 * - 공통: POST /api/payments/request(holdToken, paymentMethod) → paymentKey, orderId, amount 수신
 * - CARD: 위젯 초기화 후 requestPayment(orderId, successUrl, failUrl) 호출. successUrl에 ourPaymentKey 포함해 리다이렉트 후 승인·완료에 사용
 * - POINT: 즉시 approve(본문 없음) → complete → 완료 오버레이
 */
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

		// 카드: 주문서형 위젯. 위젯 초기화 후 requestPayment(orderId, successUrl, failUrl) 호출
		if (paymentMethod === 'CARD') {
			await initTossWidgetIfNeeded();
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
			await tossWidgets.requestPayment({
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

/** 결제 수단 변경 시: 카드 선택 시 위젯 영역 표시 및 위젯 초기화, 포인트 선택 시 숨김 */
document.querySelectorAll('input[name="paymentMethod"]').forEach((radio) => {
	radio.addEventListener('change', () => {
		if (!tossWidgetWrap) return;
		if (getPaymentMethod() === 'CARD') {
			tossWidgetWrap.classList.remove('hidden');
			tossWidgetWrap.setAttribute('aria-hidden', 'false');
			initTossWidgetIfNeeded().catch(() => {});
		} else {
			tossWidgetWrap.classList.add('hidden');
			tossWidgetWrap.setAttribute('aria-hidden', 'true');
		}
	});
});

if (overlayHome) {
	overlayHome.addEventListener('click', () => { window.location.href = '/app.html'; });
}

/**
 * 페이지 로드 시: 토스 결제 완료 후 successUrl 리다이렉트로 돌아온 경우 처리
 * - 쿼리: paymentKey, orderId, amount(토스), ourPaymentKey(우리 Payment 키)
 * - ourPaymentKey로 POST /approve(body: 토스 paymentKey/orderId/amount) → POST /complete → 완료 오버레이
 * - fail=1 이면 "카드 결제 취소/실패" 메시지만 표시
 */
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
