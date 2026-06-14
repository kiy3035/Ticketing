"""
ablation.pick_optimum / _detect_knee 단위 테스트 — 설정값 스윕에서 최적점·수확체감 판정.

결정론적이며 앱·AWS·matplotlib(차트 제외) 없이 돈다. "가드(error) 통과 후보 중 최적,
그리고 어디부터 수확체감인가"를 수치로 고정한다.
"""
import ablation


def _row(value, rps, p95, err):
    return {"value": value, "rps": rps, "p95_ms": p95, "error_rate": err}


# ── pick_optimum ──────────────────────────────────────────────────────────
def test_picks_max_rps_among_valid():
    results = [_row(30, 2500, 460, 0.03), _row(50, 2780, 410, 0.03), _row(40, 2680, 430, 0.03)]
    p = ablation.pick_optimum(results, error_cap=0.05)
    assert p["best"]["value"] == 50
    assert len(p["valid"]) == 3 and p["rejected"] == []


def test_rejects_candidate_over_error_cap():
    # 70은 rps가 가장 높지만 error_rate 0.09 > cap → 기각, 최적은 50
    results = [_row(50, 2780, 410, 0.03), _row(70, 3000, 400, 0.09)]
    p = ablation.pick_optimum(results, error_cap=0.05)
    assert p["best"]["value"] == 50
    assert p["rejected"][0]["value"] == 70


def test_all_rejected_yields_no_best():
    results = [_row(50, 2780, 410, 0.2), _row(60, 2800, 405, 0.3)]
    p = ablation.pick_optimum(results, error_cap=0.05)
    assert p["best"] is None and p["valid"] == []


# ── _detect_knee (수확체감 지점) ──────────────────────────────────────────
def test_knee_at_diminishing_returns():
    # 30→40→50 큰 폭 상승, 50→60은 +0.4%(<2%) → sweet spot = 50
    valid = [_row(30, 2500, 460, 0.03), _row(40, 2680, 430, 0.03),
             _row(50, 2780, 410, 0.03), _row(60, 2790, 408, 0.03)]
    assert ablation._detect_knee(valid, min_gain_pct=2.0) == 50


def test_knee_monotonic_increase_returns_last():
    # 끝까지 의미있게 증가하면 마지막 값
    valid = [_row(30, 2000, 500, 0.03), _row(40, 2300, 460, 0.03), _row(50, 2700, 420, 0.03)]
    assert ablation._detect_knee(valid, min_gain_pct=2.0) == 50


def test_knee_when_rps_drops():
    # 50 이후 rps 하락(증가율 음수 < 2%) → sweet spot = 50
    valid = [_row(40, 2680, 430, 0.03), _row(50, 2780, 410, 0.03), _row(60, 2750, 415, 0.03)]
    assert ablation._detect_knee(valid, min_gain_pct=2.0) == 50


# ── 리포트 렌더 ────────────────────────────────────────────────────────────
def test_render_md_marks_best():
    results = [_row(30, 2500, 460, 0.03), _row(50, 2780, 410, 0.03)]
    p = ablation.pick_optimum(results, error_cap=0.05)
    md = ablation.render_sweep_md("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", 30, results, p)
    assert "최적값 = 50" in md and "🥇 최적" in md
