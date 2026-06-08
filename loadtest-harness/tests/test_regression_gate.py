"""성능 회귀 게이트 — 비교 로직(evaluate) 검증. 네트워크/AI 불필요."""
import regression_gate as rg

_BASELINE = {
    "metrics": {
        "rps":        {"baseline": 2000, "direction": "higher_better", "max_regression_pct": 15},
        "p95_ms":     {"baseline": 500,  "direction": "lower_better",  "max_regression_pct": 25},
        "error_rate": {"baseline": 0.04, "direction": "lower_better",  "max_abs_increase": 0.03},
    }
}


def _status(result, name):
    return next(r["status"] for r in result["rows"] if r["name"] == name)


def test_기준선_근처면_통과():
    cur = {"rps": 1950, "p95_ms": 520, "error_rate": 0.05}
    result = rg.evaluate(cur, _BASELINE)
    assert result["passed"] is True
    assert all(r["status"] == "PASS" for r in result["rows"])


def test_RPS_15퍼센트_넘게_떨어지면_회귀():
    cur = {"rps": 1600, "p95_ms": 500, "error_rate": 0.04}  # -20%
    result = rg.evaluate(cur, _BASELINE)
    assert result["passed"] is False
    assert _status(result, "rps") == "REGRESS"


def test_p95_25퍼센트_넘게_오르면_회귀():
    cur = {"rps": 2000, "p95_ms": 700, "error_rate": 0.04}  # +40%
    result = rg.evaluate(cur, _BASELINE)
    assert result["passed"] is False
    assert _status(result, "p95_ms") == "REGRESS"


def test_에러율_절대증가_한도_초과시_회귀():
    cur = {"rps": 2000, "p95_ms": 500, "error_rate": 0.08}  # +0.04 > 0.03
    result = rg.evaluate(cur, _BASELINE)
    assert result["passed"] is False
    assert _status(result, "error_rate") == "REGRESS"


def test_메트릭_없으면_SKIP_이고_통과_유지():
    cur = {"rps": 2000, "p95_ms": 500}  # error_rate 누락
    result = rg.evaluate(cur, _BASELINE)
    assert _status(result, "error_rate") == "SKIP"
    assert result["passed"] is True
