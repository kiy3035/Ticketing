"""Prometheus 수집기 검증 — requests를 monkeypatch해 네트워크 없이 테스트."""
import prometheus_collector
from prometheus_collector import PrometheusCollector

_CFG = {
    "base_url": "http://prom:9090",
    "app_label": "ticketing",
    "step": "15s",
    "queries": {"rps": 'sum(rate(http_server_requests_seconds_count{application="__APP__"}[30s]))'},
}


class _FakeResp:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        pass

    def json(self):
        return self._payload


def test_app_라벨_치환됨(monkeypatch):
    # given — 호출된 PromQL을 가로채 검사
    captured = {}

    def fake_get(url, params, timeout):
        captured["query"] = params["query"]
        return _FakeResp({"status": "success", "data": {"result": []}})

    monkeypatch.setattr(prometheus_collector.requests, "get", fake_get)

    # when
    PrometheusCollector(_CFG).collect(100.0, 200.0)

    # then — __APP__ 토큰이 app_label로 치환되어 전송됨
    assert "__APP__" not in captured["query"]
    assert 'application="ticketing"' in captured["query"]


def test_멀티시리즈는_timestamp별_합산(monkeypatch):
    # given — 인스턴스 2대로 분리된 시리즈 (동일 ts)
    payload = {
        "status": "success",
        "data": {"result": [
            {"metric": {"instance": "app1"}, "values": [[100, "10"], [115, "20"]]},
            {"metric": {"instance": "app2"}, "values": [[100, "5"], [115, "7"]]},
        ]},
    }
    monkeypatch.setattr(prometheus_collector.requests, "get", lambda url, params, timeout: _FakeResp(payload))

    # when
    out = PrometheusCollector(_CFG).collect(100.0, 200.0)

    # then — 같은 timestamp끼리 합산 (10+5=15, 20+7=27)
    series = out["rps"]["series"]
    assert series == [(100.0, 15.0), (115.0, 27.0)]
    assert out["rps"]["max"] == 27.0
    assert out["rps"]["mean"] == 21.0


def test_결과_없으면_통계는_None(monkeypatch):
    # given — 빈 결과 (테스트 윈도우에 데이터 없음)
    monkeypatch.setattr(prometheus_collector.requests, "get",
                        lambda url, params, timeout: _FakeResp({"status": "success", "data": {"result": []}}))

    # when
    out = PrometheusCollector(_CFG).collect(100.0, 200.0)

    # then
    assert out["rps"]["series"] == []
    assert out["rps"]["max"] is None
    assert out["rps"]["mean"] is None
