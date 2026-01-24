const bellButton = document.getElementById('notificationBell');
const notificationPanel = document.getElementById('notificationPanel');
const notificationCount = document.getElementById('notificationCount');
const notificationList = document.getElementById('notificationList');

if (bellButton && notificationPanel && notificationCount && notificationList) {
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
			const time = item.createdAt ? new Date(item.createdAt).toLocaleString() : '';
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

	const fetchNotifications = async () => {
		try {
			const res = await fetch('/api/notifications');
			if (!res.ok) {
				return;
			}
			const data = await res.json();
			updateCount(Number(data.unreadCount || 0));
			renderNotifications(data.items || []);
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

	fetchNotifications();
	setInterval(fetchNotifications, 5000);
}
