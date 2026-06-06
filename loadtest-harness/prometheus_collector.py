"""
Prometheus 수집기 — k6 실행 시간창(start_ts~end_ts) 동안의 서버측 메트릭을
range query로 긁어온다. 사람이 Grafana를 응시하며 스크린샷 찍던 작업을 대체.

config.yaml의 prometheus.queries(PromQL)를 그대로 사용하므로,
메트릭이 바뀌면 코드가 아니라 설정만 고치면 된다.
"""
import logging
import statistics

import requests

log = logging.getLogger("harness.prom")


class PrometheusCollector:
    def __init__(self, prom_cfg: dict):
        self.base_url = prom_cfg["base_url"].rstrip("/")
        self.app_label = prom_cfg["app_label"]
        self.step = prom_cfg.get("step", "15s")
        self.queries = prom_cfg["queries"]

    def _query_range(self, promql: str, start_ts: float, end_ts: float) -> list[tuple[float, float]]:
        """range query 1건 실행 → [(ts, value)] 시계열 반환. 멀티시리즈는 동일 ts끼리 합산."""
        resp = requests.get(
            f"{self.base_url}/api/v1/query_range",
            params={"query": promql, "start": start_ts, "end": end_ts, "step": self.step},
            timeout=30,
        )
        resp.raise_for_status()
        payload = resp.json()
        if payload.get("status") != "success":
            log.error("PromQL 실패: %s — %s", promql, payload)
            return []

        results = payload["data"]["result"]
        if not results:
            return []

        # 여러 시리즈가 오면 timestamp 기준 합산 (instance 분리된 gauge 등)
        merged: dict[float, float] = {}
        for series in results:
            for ts, val in series["values"]:
                try:
                    merged[float(ts)] = merged.get(float(ts), 0.0) + float(val)
                except ValueError:
                    continue  # NaN 등은 건너뜀
        return sorted(merged.items())

    def collect(self, start_ts: float, end_ts: float) -> dict:
        """시간창 동안 config의 모든 쿼리를 수집.

        반환: { metric_name: {"series": [(ts,val)...], "max":, "mean":, "p95":, "last":} }
        """
        out = {}
        for name, raw_promql in self.queries.items():
            promql = raw_promql.replace("__APP__", self.app_label)
            try:
                series = self._query_range(promql, start_ts, end_ts)
            except requests.RequestException as e:
                log.error("[%s] Prometheus 요청 실패: %s", name, e)
                series = []

            values = [v for _, v in series]
            out[name] = {
                "series": series,
                "max": max(values) if values else None,
                "mean": statistics.fmean(values) if values else None,
                "p95": (sorted(values)[int(len(values) * 0.95)] if len(values) > 1 else
                        (values[0] if values else None)),
                "last": values[-1] if values else None,
            }
            log.info("[%s] %d 포인트 수집 (max=%s, mean=%s)",
                     name, len(series), _fmt(out[name]["max"]), _fmt(out[name]["mean"]))
        return out


def _fmt(v):
    return f"{v:.3f}" if isinstance(v, (int, float)) else "n/a"
