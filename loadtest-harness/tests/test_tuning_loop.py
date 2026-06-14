"""
tuning_loop.decide() 단위 테스트 — 전후(BEFORE→AFTER) 채택/롤백 판정.

결정론적이며 앱·AWS·API가 필요 없다. "실측이 좋아져야만 ADOPT, 악화하면 ROLLBACK"이라는
자율 루프의 안전 판정을 고정한다(AI 자기평가가 아니라 수치로).
"""
import tuning_loop

TH = {"improve_pct": 5.0, "regress_pct": 5.0, "error_abs": 0.01}
BEFORE = {"scenario": "knee-point", "rps": 2000, "p95_ms": 500, "error_rate": 0.03}


def _after(rps, p95, err):
    return {"scenario": "knee-point", "rps": rps, "p95_ms": p95, "error_rate": err}


def test_adopt_when_rps_improves():
    # RPS +10%, p95·error 안정 → ADOPT
    d = tuning_loop.decide(BEFORE, _after(2200, 495, 0.03), TH)
    assert d["verdict"] == "ADOPT"
    assert d["rps_change_pct"] == 10.0


def test_adopt_when_p95_improves():
    # p95 -10% → ADOPT
    d = tuning_loop.decide(BEFORE, _after(2010, 450, 0.03), TH)
    assert d["verdict"] == "ADOPT"


def test_rollback_when_p95_regresses():
    # p95 +20% 악화 → ROLLBACK (RPS가 좀 올라도 악화가 우선)
    d = tuning_loop.decide(BEFORE, _after(2300, 600, 0.03), TH)
    assert d["verdict"] == "ROLLBACK"


def test_rollback_when_error_regresses():
    # error_rate 절대 증가 0.05 > 허용 0.01 → ROLLBACK
    d = tuning_loop.decide(BEFORE, _after(2200, 480, 0.08), TH)
    assert d["verdict"] == "ROLLBACK"


def test_rollback_beats_improvement():
    # RPS는 크게 개선되지만 error가 악화 → 안전상 ROLLBACK
    d = tuning_loop.decide(BEFORE, _after(3000, 400, 0.09), TH)
    assert d["verdict"] == "ROLLBACK"


def test_neutral_when_change_tiny():
    # 변화 미미(±2% 미만) → NEUTRAL
    d = tuning_loop.decide(BEFORE, _after(2020, 498, 0.03), TH)
    assert d["verdict"] == "NEUTRAL"


def test_before_baseline_shape():
    # regression_gate가 먹는 baseline 스펙으로 변환되는지
    bl = tuning_loop._before_baseline(BEFORE, TH)
    assert bl["metrics"]["rps"]["direction"] == "higher_better"
    assert bl["metrics"]["error_rate"]["max_abs_increase"] == 0.01
