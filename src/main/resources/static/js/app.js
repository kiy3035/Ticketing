// 콘서트 목록을 불러와 카드로 렌더링한다.
const listEl = document.getElementById('concertList');

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
			<a class="primary" href="/app/concert/${concert.id}">좌석 보기</a>
		</div>
	`).join('');
};

const loadConcerts = async () => {
	listEl.innerHTML = '<div class="status info">콘서트 정보를 불러오는 중...</div>';
	try {
		const res = await fetch('/api/concerts');
		const data = await res.json();
		renderConcerts(data);
	} catch (error) {
		listEl.innerHTML = '<div class="status error">콘서트 정보를 불러오지 못했습니다.</div>';
	}
};

loadConcerts();
