// 회원가입 요청을 API로 전송한다.
const form = document.getElementById('signupForm');
const statusError = document.getElementById('statusError');
const statusInfo = document.getElementById('statusInfo');

form.addEventListener('submit', async (event) => {
	event.preventDefault();
	statusError.hidden = true;
	statusInfo.textContent = '아이디 4~20자, 비밀번호 6~50자';

	const username = document.getElementById('username').value.trim();
	const password = document.getElementById('password').value.trim();

	if (!username || !password) {
		statusError.textContent = '아이디와 비밀번호를 입력해주세요.';
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

	try {
		const res = await fetch('/api/auth/signup', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ username, password })
		});

		if (!res.ok) {
			if (res.status === 409) {
				throw new Error('이미 사용 중인 아이디입니다.');
			}
			if (res.status === 400) {
				throw new Error('입력값 형식이 올바르지 않습니다.');
			}
			throw new Error('회원가입에 실패했습니다.');
		}

		window.location.href = '/login.html?signup';
	} catch (error) {
		statusError.textContent = error.message;
		statusError.hidden = false;
	}
});
