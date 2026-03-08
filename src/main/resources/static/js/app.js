// 로그인 후 역할 확인 및 리다이렉팅
const checkUserRoleAndRedirect = async () => {
	try {
		const result = await window.fetchJson('/api/auth/me');
		if (result.ok) {
			const userData = result.data;
			// ADMIN 권한이면 관리자 화면으로 리다이렉팅
			if (userData.role === 'ADMIN') {
				window.location.href = '/admin.html';
			}
		}
	} catch (error) {
		// 에러 발생 시 현재 페이지에서 계속 진행
	}
};

checkUserRoleAndRedirect();

// 콘서트 목록을 불러와 카드로 렌더링한다.
const listEl = document.getElementById('concertList');
const statActiveUsers = document.getElementById('statActiveUsers');
const statTodayOpen = document.getElementById('statTodayOpen');
const statSuccessRate = document.getElementById('statSuccessRate');
const categoryButtons = document.querySelectorAll('.chip[data-category]');
const searchInput = document.getElementById('searchInput');
let allConcerts = [];
let selectedCategory = 'ALL';
let showPast = false; // false: 예매 가능, true: 지난 공연
let searchTimer = null;
const concertCache = new Map();
const CACHE_TTL_MS = 30000;

const formatDate = (value) => {
	const date = new Date(value);
	return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

const renderConcerts = (concerts) => {
	if (!concerts.length) {
		listEl.innerHTML = '<div class="status info">' + (showPast ? '지난 공연이 없습니다.' : '등록된 콘서트가 없습니다.') + '</div>';
		return;
	}

	listEl.innerHTML = concerts.map((concert) => {
		const isPast = showPast;
		const label = isPast ? '상세 보기' : '좌석 보기';
		const href = isPast ? `/concert.html?concertId=${concert.id}` : '#';
		const attrs = isPast ? '' : ` data-queue-check="true" data-concert-id="${concert.id}"`;
		return `
		<div class="card">
			<h3>${concert.title}</h3>
			<div class="meta">${concert.venue}</div>
			<div class="meta">${formatDate(concert.concertAt)}</div>
			<div class="meta">상태: ${concert.status}</div>
			<a class="primary" href="${href}"${attrs}>${label}</a>
		</div>
	`;
	}).join('');
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

const buildCacheKey = (query, category, past) => `${category || 'ALL'}::${query || ''}::${past ? 'past' : 'upcoming'}`;

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

const buildQueryString = (query, category, past) => {
	const params = new URLSearchParams();
	if (query) params.set('query', query);
	if (category && category !== 'ALL') params.set('category', category);
	if (past) params.set('past', 'true');
	return params.toString();
};

const loadConcerts = async () => {
	listEl.innerHTML = '<div class="status info">콘서트 정보를 불러오는 중...</div>';
	try {
		const query = searchInput ? searchInput.value.trim() : '';
		const cacheKey = buildCacheKey(query, selectedCategory, showPast);
		const allKey = buildCacheKey('', 'ALL', showPast);
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

		const qs = buildQueryString(query, selectedCategory, showPast);
		const result = await window.fetchJson(qs ? `/api/concerts?${qs}` : '/api/concerts');
		if (!result.ok) throw new Error('fetch failed');
		allConcerts = Array.isArray(result.data) ? result.data : [];
		setCachedConcerts(cacheKey, allConcerts);
		if (!query && selectedCategory === 'ALL') setCachedConcerts(allKey, allConcerts);
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

// 예매 가능 / 지난 공연 탭
document.querySelectorAll('.app-tab').forEach((btn) => {
	btn.addEventListener('click', () => {
		document.querySelectorAll('.app-tab').forEach((b) => b.classList.remove('active'));
		btn.classList.add('active');
		showPast = btn.dataset.past === 'true';
		loadConcerts();
	});
});

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

// 패턴 B: 대기열 필요 시에만 queue 페이지로, 아니면 바로 좌석 페이지로
listEl.addEventListener('click', async (e) => {
	const link = e.target.closest('a[data-queue-check][data-concert-id]');
	if (!link) return;
	e.preventDefault();
	const concertId = link.getAttribute('data-concert-id');
	if (!concertId) return;
	try {
		const result = await window.fetchJson(`/api/queue/required?concertId=${concertId}`);
		const required = result.ok && result.data && result.data.required;
		window.location.href = required
			? `/queue.html?concertId=${concertId}`
			: `/concert.html?concertId=${concertId}`;
	} catch (err) {
		window.location.href = `/concert.html?concertId=${concertId}`;
	}
});

loadConcerts();
loadMetrics();

setInterval(loadMetrics, 5000);