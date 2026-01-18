// 로그인 상태 메시지를 쿼리 파라미터로 표시한다.
const params = new URLSearchParams(window.location.search);
const statusOk = document.getElementById('statusOk');
const statusError = document.getElementById('statusError');
const statusLogout = document.getElementById('statusLogout');

if (params.has('signup')) {
	statusOk.hidden = false;
}
if (params.has('error')) {
	statusError.hidden = false;
}
if (params.has('logout')) {
	statusLogout.hidden = false;
}
