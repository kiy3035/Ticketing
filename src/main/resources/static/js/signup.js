// 회원가입 요청을 API로 전송한다.
const form = document.getElementById('signupForm');
const statusError = document.getElementById('statusError');
const statusInfo = document.getElementById('statusInfo');
const notificationMethod = document.getElementById('notificationMethod');
const signupRole = document.getElementById('signupRole');
const toggleBtns = document.querySelectorAll('.notification-preference .toggle-btn');
const roleBtns = document.querySelectorAll('.role-preference .toggle-btn');

// 알림 방식 토글
toggleBtns.forEach(btn => {
	btn.addEventListener('click', (e) => {
		e.preventDefault();
		toggleBtns.forEach(b => b.classList.remove('active'));
		btn.classList.add('active');
		notificationMethod.value = btn.dataset.method;
	});
});

// 가입 유형 토글 (일반 고객 / 판매자)
roleBtns.forEach(btn => {
	btn.addEventListener('click', (e) => {
		e.preventDefault();
		roleBtns.forEach(b => b.classList.remove('active'));
		btn.classList.add('active');
		if (signupRole) signupRole.value = btn.dataset.role || 'USER';
	});
});

// 휴대폰번호 포맷팅
const phoneInput = document.getElementById('phone');
phoneInput.addEventListener('input', (e) => {
	let value = e.target.value.replace(/\D/g, '');
	if (value.length > 11) value = value.slice(0, 11);
	
	if (value.length <= 3) {
		e.target.value = value;
	} else if (value.length <= 7) {
		e.target.value = value.slice(0, 3) + '-' + value.slice(3);
	} else {
		e.target.value = value.slice(0, 3) + '-' + value.slice(3, 7) + '-' + value.slice(7);
	}
});

form.addEventListener('submit', async (event) => {
	event.preventDefault();
	statusError.hidden = true;
	statusInfo.textContent = '회원가입을 진행 중입니다...';

	const username = document.getElementById('username').value.trim();
	const password = document.getElementById('password').value.trim();
	const email = document.getElementById('email').value.trim();
	const phone = document.getElementById('phone').value.trim();
	const notifMethod = notificationMethod.value;
	const role = signupRole ? signupRole.value : 'USER';

	if (!username || !password || !email || !phone) {
		statusError.textContent = '모든 필수 항목을 입력해주세요.';
		statusError.hidden = false;
		return;
	}
	if (username.length < 4 || username.length > 20) {
		statusError.textContent = '아이디는 4~20자로 입력해주세요.';
		statusError.hidden = false;
		return;
	}
	if (password.length < 6 || password.length > 50) {
		statusError.textContent = '비밀번호는 6~50자로 입력해주세요.';
		statusError.hidden = false;
		return;
	}
	if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
		statusError.textContent = '유효한 이메일 주소를 입력해주세요.';
		statusError.hidden = false;
		return;
	}
	if (!/^\d{3}-\d{4}-\d{4}$/.test(phone)) {
		statusError.textContent = '휴대폰번호를 올바른 형식으로 입력해주세요. (010-0000-0000)';
		statusError.hidden = false;
		return;
	}

	try {
		const result = await window.fetchJson('/api/auth/signup', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ 
				username, 
				password,
				email,
				phone,
				notificationMethod: notifMethod,
				role: role === 'SELLER' ? 'SELLER' : 'USER'
			})
		});
		if (!result.ok) {
			if (result.status === 409) {
				throw new Error('이미 사용 중인 아이디입니다.');
			}
			if (result.status === 400) {
				throw new Error(result.error?.message || '입력값 형식이 올바르지 않습니다.');
			}
			throw new Error(result.error?.message || '회원가입에 실패했습니다.');
		}

		window.location.href = '/login.html?signup';
	} catch (error) {
		statusError.textContent = error.message;
		statusError.hidden = false;
	}
});
