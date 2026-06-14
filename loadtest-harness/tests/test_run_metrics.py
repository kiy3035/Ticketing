"""
run.py의 metrics.json 집계(aggregate_signals/build_metrics) 단위 테스트.

advisor.py가 먹는 입력을 만드는 부분이라, 앱 없이 결정론적으로 형태·집계를 고정한다.
"""
from types import SimpleNamespace

import run


def _r(idx, rps, p95, fail):
    """k6 회차 스텁: run.py가 쓰는 .run_index / .summary 만 흉내."""
    return SimpleNamespace(run_index=idx, summary={
        "http_reqs_rate": rps, "http_req_duration_p95": p95, "http_req_failed_rate": fail})


# ── aggregate_signals ─────────────────────────────────────────────────────
def test_aggregate_signals_max_of_max_mean_of_mean():
    prom = [
        {"hikari_pending": {"max": 10, "mean": 4}},
        {"hikari_pending": {"max": 18, "mean": 8}},
    ]
    out = run.aggregate_signals(prom)
    assert out["hikari_pending"]["max"] == 18      # 최댓값들의 max(최악값)
    assert out["hikari_pending"]["mean"] == 6.0     # 평균들의 평균


def test_aggregate_signals_skips_none_and_missing():
    prom = [
        {"x": {"max": None, "mean": None}},          # 수집 실패 회차
        {"x": {"max": 5, "mean": 3}},
    ]
    out = run.aggregate_signals(prom)
    assert out["x"] == {"max": 5, "mean": 3}


def test_aggregate_signals_all_missing_yields_none():
    out = run.aggregate_signals([{"x": {}}, {"x": {}}])
    assert out["x"] == {"max": None, "mean": None}


# ── build_metrics: hot 회차(run1 제외) + signals 결합 ─────────────────────
def test_build_metrics_excludes_cold_run1():
    runs = [_r(1, 100, 999, 0.5), _r(2, 200, 50, 0.01), _r(3, 220, 60, 0.03)]
    prom = [
        {"hikari_pending": {"max": 99, "mean": 99}},   # cold — 제외돼야 함
        {"hikari_pending": {"max": 10, "mean": 5}},
        {"hikari_pending": {"max": 12, "mean": 7}},
    ]
    m = run.build_metrics("knee-point", runs, prom)
    assert m["scenario"] == "knee-point"
    assert m["rps"] == 210            # (200+220)/2 — run1 제외
    assert m["signals"]["hikari_pending"]["max"] == 12    # cold의 99 제외됨


def test_build_metrics_shape_matches_advisor_input():
    runs = [_r(1, 100, 50, 0.01), _r(2, 100, 50, 0.01)]
    m = run.build_metrics("s", runs, [{}, {"q": {"max": 1, "mean": 1}}])
    # advisor가 기대하는 키들이 다 있는지
    assert set(m) >= {"scenario", "rps", "p95_ms", "error_rate", "signals"}
