// 대기열 페이지에서 대기 순번 폴링 및 입장 허용 확인을 처리한다.
const params = new URLSearchParams(window.location.search);
const concertId = params.get('concertId');
const concertTitle = document.getElementById('concertTitle');
const concertMeta = document.getElementById('concertMeta');
const currentRank = document.getElementById('currentRank');
const totalWaiting = document.getElementById('totalWaiting');
const queueMessage = document.getElementById('queueMessage');

let queueToken = null;
let pollInterval = null;

// 콘서트 정보 로드
const loadConcertInfo = async () => {
	if (!concertId) {
		queueMessage.textContent = '잘못된 접근입니다.';
		return;
	}

	try {
		const result = await window.fetchJson('/api/concerts');
		const concerts = Array.isArray(result.data) ? result.data : [];
		const concert = concerts.find((item) => String(item.id) === String(concertId));
		if (concert) {
			concertTitle.textContent = concert.title;
			concertMeta.textContent = `${concert.venue} | ${new Date(concert.startAt).toLocaleString()}`;
		}
	} catch (error) {
		// 콘서트 정보는 실패해도 대기열 진입은 진행한다.
	}
};

// 대기열 진입
const enterQueue = async () => {
	if (!concertId) {
		queueMessage.textContent = '콘서트 ID가 없습니다.';
		return;
	}

	try {
		// 캐시 무시를 위해 타임스탬프 추가
		const timestamp = new Date().getTime();
		const result = await window.fetchJson(`/api/queue/enter?concertId=${concertId}&_t=${timestamp}`, {
			method: 'POST',
			headers: {
				'Cache-Control': 'no-cache',
				'Pragma': 'no-cache'
			}
		});
		if (!result.ok) {
			throw new Error(result.error?.message || '대기열 진입 실패');
		}
		
		// 디버깅: 실제 응답 값 확인
		console.log('대기열 진입 응답:', result.data);
		
		queueToken = result.data.token;
		currentRank.textContent = result.data.rank || '-';
		totalWaiting.textContent = result.data.totalWaiting || '-';
		queueMessage.textContent = '대기열에 진입했습니다. 순번이 되면 자동으로 입장됩니다.';
		
		// 순번 폴링 시작
		startPolling();
	} catch (error) {
		console.error('대기열 진입 에러:', error);
		queueMessage.textContent = `대기열 진입 실패: ${error.message}`;
	}
};

// 순번 폴링 시작
const startPolling = () => {
	if (pollInterval) {
		clearInterval(pollInterval);
	}
	
	// 즉시 한 번 실행
	checkQueueStatus();
	
	// 2초마다 폴링
	pollInterval = setInterval(checkQueueStatus, 2000);
};

// 대기열 상태 확인
const checkQueueStatus = async () => {
	if (!queueToken || !concertId) {
		return;
	}

	try {
		// 캐시 무시를 위해 타임스탬프 추가
		const timestamp = new Date().getTime();
		const result = await window.fetchJson(`/api/queue/status?token=${queueToken}&concertId=${concertId}&_t=${timestamp}`, {
			headers: {
				'Cache-Control': 'no-cache',
				'Pragma': 'no-cache'
			}
		});
		
		// 디버깅: 실제 응답 값 확인
		console.log('대기열 상태 응답:', result.data);
		
		if (!result.ok) {
			// 토큰이 유효하지 않거나 대기열에서 제거된 경우
			if (result.error?.message?.includes('not found') || result.error?.message?.includes('Token')) {
				clearInterval(pollInterval);
				currentRank.textContent = '-';
				totalWaiting.textContent = '-';
				queueMessage.textContent = '대기열 토큰이 만료되었습니다. 다시 진입해주세요.';
				// 3초 후 자동으로 다시 진입 시도
				setTimeout(() => {
					enterQueue();
				}, 3000);
				return;
			}
			throw new Error(result.error?.message || '상태 조회 실패');
		}
		
		// 응답 데이터 검증
		const rank = result.data.rank;
		const totalWaitingCount = result.data.totalWaiting;
		
		// rank나 totalWaiting이 null이거나 undefined인 경우 처리
		if (rank == null || totalWaitingCount == null) {
			console.warn('대기열 상태 데이터가 없습니다:', result.data);
			currentRank.textContent = '-';
			totalWaiting.textContent = '-';
			return;
		}
		
		currentRank.textContent = rank || '-';
		totalWaiting.textContent = totalWaitingCount || '-';
		
		// 입장 허용 여부 확인
		if (result.data.isAllowed) {
			// 입장 허용됨 - 좌석 예매 화면으로 이동
			clearInterval(pollInterval);
			queueMessage.textContent = '입장이 허용되었습니다. 좌석 선택 화면으로 이동합니다...';
			setTimeout(() => {
				window.location.href = `/concert.html?concertId=${concertId}&queueToken=${queueToken}`;
			}, 1000);
			return;
		}
		
		// 대기 중 메시지 업데이트
		if (rank && totalWaitingCount) {
			const estimatedWait = Math.max(1, Math.ceil(rank / 50));
			queueMessage.textContent = `대기 중입니다. 예상 대기 시간: 약 ${estimatedWait}분`;
		}
	} catch (error) {
		console.error('대기열 상태 확인 실패:', error);
		// 네트워크 에러 등 기타 에러 발생 시에도 화면 초기화
		currentRank.textContent = '-';
		totalWaiting.textContent = '-';
		queueMessage.textContent = '대기열 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.';
	}
};

// 입장 허용 확인 (별도 API 호출)
const checkAllowed = async () => {
	if (!queueToken) {
		return false;
	}

	try {
		const result = await window.fetchJson(`/api/queue/allowed?token=${queueToken}`);
		if (!result.ok) {
			return false;
		}
		return result.data.allowed && result.data.concertId && String(result.data.concertId) === String(concertId);
	} catch (error) {
		return false;
	}
};

// 페이지 로드 시 초기화
loadConcertInfo().then(() => {
	enterQueue();
});

// 페이지 언로드 시 정리
window.addEventListener('beforeunload', () => {
	if (pollInterval) {
		clearInterval(pollInterval);
	}
});
