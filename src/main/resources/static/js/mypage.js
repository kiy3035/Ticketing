const profileNameEl = document.getElementById('profileName');
const profileBalanceEl = document.getElementById('profileBalance');
const profileCreatedAtEl = document.getElementById('profileCreatedAt');
const profileStatusEl = document.getElementById('profileStatus');

const formatNumber = (value) => {
	if (value === null || value === undefined) {
		return '-';
	}
	return Number(value).toLocaleString();
};

const formatDate = (value) => (window.formatDateKorea ? window.formatDateKorea(value) : new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }));

const loadProfile = async () => {
	if (!profileNameEl || !profileBalanceEl || !profileCreatedAtEl) {
		return;
	}
	profileStatusEl.textContent = '내 정보를 불러오는 중...';
	try {
		const result = await window.fetchJson('/api/auth/me/profile');
		if (!result.ok) {
			throw new Error('fetch failed');
		}
		const data = result.data || {};
		profileNameEl.textContent = data.username || '-';
		profileBalanceEl.textContent = `${formatNumber(data.point)} P`;
		profileCreatedAtEl.textContent = formatDate(data.createdAt);
		profileStatusEl.textContent = '';
	} catch (error) {
		profileStatusEl.textContent = '내 정보를 불러오지 못했습니다.';
		profileNameEl.textContent = '-';
		profileBalanceEl.textContent = '-';
		profileCreatedAtEl.textContent = '-';
	}
};

loadProfile();
