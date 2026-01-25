// 콘서트 목록을 불러와 카드로 렌더링한다.
const listEl = document.getElementById('concertList');
const statActiveUsers = document.getElementById('statActiveUsers');
const statTodayOpen = document.getElementById('statTodayOpen');
const statSuccessRate = document.getElementById('statSuccessRate');
const categoryButtons = document.querySelectorAll('.chip[data-category]');
const searchInput = document.getElementById('searchInput');
let allConcerts = [];
let selectedCategory = 'ALL';
let searchTimer = null;
const concertCache = new Map();
const CACHE_TTL_MS = 30000;

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

const applyClientFilters = (concerts) => {
	const query = searchInput ? searchInput.value.trim().toLowerCase() : '';
	return concerts.filter((concert) => {
		const matchesCategory = selectedCategory === 'ALL' || concert.category === selectedCategory;
		if (!matchesCategory) {
			return false;
		}
		if (!query) {
			return true;
		}
		const title = (concert.title || '').toLowerCase();
		const venue = (concert.venue || '').toLowerCase();
		return title.includes(query) || venue.includes(query);
	});
};

const buildCacheKey = (query, category) => `${category || 'ALL'}::${query || ''}`;

const getCachedConcerts = (key) => {
	const entry = concertCache.get(key);
	if (!entry) {
		return null;
	}
	if (Date.now() - entry.timestamp > CACHE_TTL_MS) {
		concertCache.delete(key);
		return null;
	}
	return entry.data;
};

const setCachedConcerts = (key, data) => {
	concertCache.set(key, { timestamp: Date.now(), data });
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

const loadConcerts = async () => {
	listEl.innerHTML = '<div class="status info">콘서트 정보를 불러오는 중...</div>';
	try {
		const query = searchInput ? searchInput.value.trim() : '';
		const cacheKey = buildCacheKey(query, selectedCategory);
		const allKey = buildCacheKey('', 'ALL');
		if (!query && selectedCategory !== 'ALL') {
			const cachedAll = getCachedConcerts(allKey);
			if (cachedAll) {
				allConcerts = cachedAll;
				renderConcerts(applyClientFilters(allConcerts));
				return;
			}
		}
		const cached = getCachedConcerts(cacheKey);
		if (cached) {
			allConcerts = cached;
			renderConcerts(applyClientFilters(allConcerts));
			return;
		}

		const qs = buildQueryString(query, selectedCategory);
		const result = await window.fetchJson(qs ? `/api/concerts?${qs}` : '/api/concerts');
		if (!result.ok) {
			throw new Error('fetch failed');
		}
		allConcerts = Array.isArray(result.data) ? result.data : [];
		setCachedConcerts(cacheKey, allConcerts);
		if (!query && selectedCategory === 'ALL') {
			setCachedConcerts(allKey, allConcerts);
		}
		renderConcerts(applyClientFilters(allConcerts));
	} catch (error) {
		listEl.innerHTML = '<div class="status error">콘서트 정보를 불러오지 못했습니다.</div>';
	}
};

const loadMetrics = async () => {
	try {
		const result = await window.fetchJson('/api/metrics');
		if (!result.ok) {
			throw new Error('fetch failed');
		}
		statActiveUsers.textContent = result.data.activeUsers.toLocaleString();
		statTodayOpen.textContent = `${result.data.todayOpen} 공연`;
		statSuccessRate.textContent = `${result.data.successRate.toFixed(1)}%`;
	} catch (error) {
		statActiveUsers.textContent = '-';
		statTodayOpen.textContent = '-';
		statSuccessRate.textContent = '-';
	}
};

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

loadConcerts();
loadMetrics();

setInterval(loadMetrics, 5000);