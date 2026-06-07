"""분석기 검증 — 프롬프트 직렬화와 키 없을 때 graceful skip (API 호출 없음)."""
import analyzer
from k6_runner import RunResult


def _runs():
    return [RunResult("knee-point", 1, 0, 0,
                      {"http_reqs_rate": 820.5, "http_req_duration_p95": 95.2,
                       "http_req_duration_max": 410.0, "http_req_failed_rate": 0.0,
                       "http_reqs_count": 49230, "vus_max": 1500}, "", 0)]


def _prom():
    return [{"rps": {"max": 820.0, "mean": 700.0}}]


def test_프롬프트에_회차표와_서버메트릭_포함():
    # when
    prompt = analyzer._build_user_prompt("knee-point", _runs(), _prom())

    # then — k6 표 + Prometheus 섹션이 들어감
    assert "시나리오: knee-point" in prompt
    assert "k6 회차별 클라이언트 메트릭" in prompt
    assert "1회차" in prompt
    assert "rps: max=820.00" in prompt


def test_API키_없으면_분석_건너뜀(monkeypatch):
    # given — 키 미설정
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)

    # when
    result = analyzer.analyze("knee-point", _runs(), _prom(),
                              {"enabled": True, "model": "gemini-2.5-flash"})

    # then — API 호출 없이 안내 문구 반환 (파이프라인 중단 안 함)
    assert "GEMINI_API_KEY" in result


def test_비활성화시_건너뜀():
    # when — config에서 analyzer.enabled=false
    result = analyzer.analyze("knee-point", _runs(), _prom(),
                              {"enabled": False, "model": "gemini-2.5-flash"})

    # then
    assert "비활성화" in result
