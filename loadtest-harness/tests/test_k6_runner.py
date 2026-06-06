"""k6 summary JSON 파싱 검증 — 앱/ k6 없이 순수 함수만 테스트."""
from k6_runner import _extract_metrics


def test_extract_metrics_정상_요약_평탄화():
    # given — k6 --summary-export 형태의 요약 JSON
    summary = {
        "metrics": {
            "http_reqs": {"count": 49230, "rate": 820.5},
            "http_req_duration": {"avg": 35.1, "p(95)": 95.2, "max": 410.0},
            "http_req_failed": {"value": 0.0341},
            "iterations": {"count": 12000},
            "vus_max": {"value": 1500},
        }
    }

    # when
    m = _extract_metrics(summary)

    # then — 포폴에 쓰는 핵심 지표가 평탄화돼 추출됨
    assert m["http_reqs_count"] == 49230
    assert m["http_reqs_rate"] == 820.5
    assert m["http_req_duration_p95"] == 95.2
    assert m["http_req_failed_rate"] == 0.0341
    assert m["vus_max"] == 1500


def test_extract_metrics_키_없으면_None_안전():
    # given — 일부 메트릭이 누락된 요약 (k6 버전/시나리오 차이)
    summary = {"metrics": {"http_reqs": {"count": 10}}}

    # when
    m = _extract_metrics(summary)

    # then — 누락 키는 예외 없이 None
    assert m["http_reqs_count"] == 10
    assert m["http_req_duration_p95"] is None
    assert m["http_req_failed_rate"] is None


def test_extract_metrics_빈_요약():
    # given / when — metrics 키 자체가 없는 경우
    m = _extract_metrics({})

    # then — 전부 None, 예외 없음
    assert all(v is None for v in m.values())
