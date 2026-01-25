// 콘서트 목록을 불러와 카드로 렌더링한다.
const listEl = document.getElementById('concertList');
const userNameEl = document.getElementById('userName');
const logoutBtn = document.getElementById('logoutBtn');
const statActiveUsers = document.getElementById('statActiveUsers');
const statTodayOpen = document.getElementById('statTodayOpen');
const statSuccessRate = document.getElementById('statSuccessRate');
const categoryButtons = document.querySelectorAll('.chip[data-category]');
const searchInput = document.getElementById('searchInput');
let allConcerts = [];
let selectedCategory = 'ALL';
let searchTimer = null;

const formatDate = (value) => {
	const date = new Date(value);
	return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

const renderConcerts = (concerts) => {
	if (!concerts.length) {
		listEl.innerHTML = '<div class="status info">등록된 콘서트가 없습니다.</div>';
		return;
	}

	listEl.innerHTML = concerts.map((concert) => `
		<div class="card">
			<h3>${concert.title}</h3>
			<div class="meta">${concert.venue}</div>
			<div class="meta">${formatDate(concert.startAt)} ~ ${formatDate(concert.endAt)}</div>
			<div class="meta">상태: ${concert.status}</div>
			<a class="primary" href="/concert.html?concertId=${concert.id}">좌석 보기</a>
		</div>
	`).join('');
};

const buildQueryString = (query, category) => {
	const params = new URLSearchParams();
	if (query) {
		params.set('query', query);
	}
	if (category && category !== 'ALL') {
		params.set('category', category);
	}
	return params.toString();
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

const loadConcerts = async () => {
	listEl.innerHTML = '<div class="status info">콘서트 정보를 불러오는 중...</div>';
	try {
		const query = searchInput ? searchInput.value.trim() : '';
		const qs = buildQueryString(query, selectedCategory);
		const res = await fetch(qs ? `/api/concerts?${qs}` : '/api/concerts');
		const data = await res.json();
		allConcerts = Array.isArray(data) ? data : [];
		renderConcerts(allConcerts);
	} catch (error) {
		listEl.innerHTML = '<div class="status error">콘서트 정보를 불러오지 못했습니다.</div>';
	}
};

const loadMetrics = async () => {
	try {
		const res = await fetch('/api/metrics');
		const data = await res.json();
		statActiveUsers.textContent = data.activeUsers.toLocaleString();
		statTodayOpen.textContent = `${data.todayOpen} 공연`;
		statSuccessRate.textContent = `${data.successRate.toFixed(1)}%`;
	} catch (error) {
		statActiveUsers.textContent = '-';
		statTodayOpen.textContent = '-';
		statSuccessRate.textContent = '-';
	}
};

const logout = async () => {
	try {
		await fetch('/logout', { method: 'POST' });
	} finally {
		window.location.href = '/login.html?logout';
	}
};

logoutBtn.addEventListener('click', logout);
categoryButtons.forEach((button) => {
	button.addEventListener('click', () => {
		categoryButtons.forEach((item) => item.classList.remove('active'));
		button.classList.add('active');
		selectedCategory = button.dataset.category;
		loadConcerts();
	});
});

if (searchInput) {
	searchInput.addEventListener('input', () => {
		if (searchTimer) {
			clearTimeout(searchTimer);
		}
		searchTimer = setTimeout(loadConcerts, 300);
	});
}

loadUser();
loadConcerts();
loadMetrics();

setInterval(loadMetrics, 5000);