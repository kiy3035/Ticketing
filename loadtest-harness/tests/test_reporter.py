"""리포트 집계 검증 — 회차 평균±표준편차 계산이 정확한지 (수기 계산 대체)."""
from k6_runner import RunResult
from reporter import _aggregate_table


def _run(idx, rps, p95, mx, fail):
    return RunResult("s", idx, 0, 0,
                     {"http_reqs_rate": rps, "http_req_duration_p95": p95,
                      "http_req_duration_max": mx, "http_req_failed_rate": fail},
                     "", 0)


def test_평균과_표준편차_계산():
    # given — 3회차 (실패율은 0~1 → %로 변환되어야 함)
    runs = [_run(1, 820.5, 95.2, 410.0, 0.0),
            _run(2, 810.1, 102.7, 520.0, 0.0102),
            _run(3, 805.9, 110.3, 600.0, 0.0341)]

    # when
    table = _aggregate_table(runs)

    # then — 평균 RPS 평균값과 실패율 %변환 확인
    assert "812.17" in table          # (820.5+810.1+805.9)/3
    assert "± 6.14" in table          # 모표준편차
    assert "3.41" in table            # 0.0341 → 3.41%
    assert "| 평균 RPS |" in table


def test_일부_None이어도_죽지_않음():
    # given — 한 회차 지표 누락
    runs = [_run(1, 820.5, 95.2, 410.0, 0.0),
            RunResult("s", 2, 0, 0, {}, "", 99)]

    # when
    table = _aggregate_table(runs)

    # then — n/a로 표기되고 예외 없음
    assert "n/a" in table
