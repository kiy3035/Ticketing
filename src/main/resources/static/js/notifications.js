const initNotifications = () => {
	const bellButton = document.getElementById('notificationBell');
	const notificationPanel = document.getElementById('notificationPanel');
	const notificationCount = document.getElementById('notificationCount');
	const notificationList = document.getElementById('notificationList');

	if (!bellButton || !notificationPanel || !notificationCount || !notificationList) {
		return;
	}

	let eventSource = null;

	const renderNotifications = (items) => {
		notificationList.innerHTML = '';
		if (!items.length) {
			const empty = document.createElement('li');
			empty.className = 'notification-empty';
			empty.textContent = '새로운 알림이 없습니다.';
			notificationList.appendChild(empty);
			return;
		}
		items.forEach((item) => {
			const li = document.createElement('li');
			li.className = 'notification-item';
			const time = item.createdAt ? (window.formatDateKorea || ((v) => new Date(v).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })))(item.createdAt) : '';
			li.innerHTML = `
				<div class="message">${item.message}</div>
				<div class="time">${time}</div>
			`;
			notificationList.appendChild(li);
		});
	};

	const updateCount = (count) => {
		if (count > 0) {
			notificationCount.textContent = String(count);
			notificationCount.classList.remove('hidden');
		} else {
			notificationCount.textContent = '0';
			notificationCount.classList.add('hidden');
		}
	};

	// SSE로 실시간 알림 수신
	const connectSSE = () => {
		// 기존 연결이 있으면 종료
		if (eventSource) {
			eventSource.close();
		}

		// 새 SSE 연결 생성
		eventSource = new EventSource('/api/notifications/stream');

		// 알림 수신 시 처리
		eventSource.addEventListener('notification', (event) => {
			try {
				const notification = JSON.parse(event.data);
				// 알림 카운트 증가
				const currentCount = Number(notificationCount.textContent || '0');
				updateCount(currentCount + 1);
				
				// 알림 패널이 열려있으면 목록 갱신
				if (notificationPanel.classList.contains('open')) {
					fetchNotifications();
				}
			} catch (error) {
				console.error('알림 파싱 실패:', error);
			}
		});

		// 연결 오류 시 재연결 시도
		eventSource.onerror = (error) => {
			console.error('SSE 연결 오류:', error);
			eventSource.close();
			// 3초 후 재연결 시도
			setTimeout(connectSSE, 3000);
		};
	};

	const fetchNotifications = async () => {
		try {
			const result = await window.fetchJson('/api/notifications');
			if (!result.ok) {
				return;
			}
			updateCount(Number(result.data?.unreadCount || 0));
			renderNotifications(result.data?.items || []);
		} catch (error) {
			// 알림 실패는 무시한다.
		}
	};

	const clearNotifications = async () => {
		try {
			await fetch('/api/notifications', { method: 'DELETE' });
		} catch (error) {
			// 실패 시 무시한다.
		}
	};

	bellButton.addEventListener('click', async () => {
		const isOpen = notificationPanel.classList.toggle('open');
		if (isOpen) {
			await fetchNotifications();
			await clearNotifications();
			updateCount(0);
		}
	});

	document.addEventListener('click', (event) => {
		if (!notificationPanel.contains(event.target) && !bellButton.contains(event.target)) {
			notificationPanel.classList.remove('open');
		}
	});

	// 초기화: SSE 연결 및 폴링 시작
	fetchNotifications();
	connectSSE();
	
	// 폴링은 백업용으로 유지 (SSE 연결이 끊겼을 때 대비)
	// 주기를 30초로 늘려서 서버 부하 감소
	setInterval(fetchNotifications, 30000);

	// 페이지 언로드 시 SSE 연결 종료
	window.addEventListener('beforeunload', () => {
		if (eventSource) {
			eventSource.close();
		}
	});
};

window.initNotifications = initNotifications;
