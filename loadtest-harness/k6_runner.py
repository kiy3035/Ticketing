"""
k6 실행기 — 시나리오를 N회 반복 실행하고 회차별 요약 메트릭과 실행 시간창을 수집한다.

기존엔 사람이 k6를 1회차/2회차/3회차 수동 실행 → 그 반복 작업을 자동화하는 모듈.
각 회차의 (시작, 종료) epoch 시각을 기록해 두면, prometheus_collector가
정확히 그 구간의 서버 메트릭을 range query로 긁어올 수 있다.
"""
import json
import logging
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger("harness.k6")


@dataclass
class RunResult:
    """k6 단일 회차 실행 결과."""
    scenario: str
    run_index: int            # 1-based 회차 번호
    start_ts: float           # epoch seconds — Prometheus range query 시작
    end_ts: float             # epoch seconds — Prometheus range query 종료
    summary: dict = field(default_factory=dict)   # k6 end-of-test 요약 (가공본)
    raw_summary_path: str = ""                     # 원본 summary JSON 경로 (검증용 보관)
    return_code: int = 0


def _extract_metrics(summary_json: dict) -> dict:
    """k6 --summary-export JSON에서 포트폴리오에 필요한 핵심 지표만 추출.

    knee point/bottleneck 판단에 쓰는 값 위주로 평탄화한다.
    k6 버전에 따라 키가 없을 수 있어 모두 안전하게 get 처리.
    """
    metrics = summary_json.get("metrics", {})

    def m(name, key, default=None):
        return metrics.get(name, {}).get(key, default)

    return {
        "http_reqs_count": m("http_reqs", "count"),
        "http_reqs_rate": m("http_reqs", "rate"),               # 평균 RPS
        "http_req_duration_avg": m("http_req_duration", "avg"),
        "http_req_duration_p95": m("http_req_duration", "p(95)"),
        "http_req_duration_max": m("http_req_duration", "max"),
        "http_req_failed_rate": m("http_req_failed", "value"),  # 실패율 0~1
        "iterations": m("iterations", "count"),
        "vus_max": m("vus_max", "value") or m("vus_max", "max"),
    }


def run_scenario(scenario: dict, k6_cfg: dict, harness_dir: Path) -> list[RunResult]:
    """하나의 시나리오를 repeat 횟수만큼 실행하고 회차별 RunResult 리스트 반환."""
    name = scenario["name"]
    script = (harness_dir / scenario["script"]).resolve()
    if not script.exists():
        raise FileNotFoundError(f"k6 스크립트를 찾을 수 없음: {script}")

    repeat = int(k6_cfg.get("repeat", 1))
    results: list[RunResult] = []

    # k6 -e 로 넘길 환경변수: 공통(base_url, concert_id) + 시나리오별 env
    base_env = {
        "BASE_URL": k6_cfg["base_url"],
        "CONCERT_ID": str(k6_cfg["concert_id"]),
    }
    base_env.update({k: str(v) for k, v in (scenario.get("env") or {}).items()})

    for i in range(1, repeat + 1):
        # 회차별 summary JSON을 별도 파일로 — 원본 보관 = 면접 시 검증 가능성 확보
        summary_path = harness_dir / "reports" / "_raw" / f"{name}_run{i}_summary.json"
        summary_path.parent.mkdir(parents=True, exist_ok=True)

        cmd = [k6_cfg.get("binary", "k6"), "run", "--summary-export", str(summary_path)]
        for key, val in base_env.items():
            cmd += ["-e", f"{key}={val}"]
        cmd.append(str(script))

        log.info("[%s] %d/%d 회차 실행 시작 — %s", name, i, repeat, " ".join(cmd))
        start_ts = time.time()
        proc = subprocess.run(cmd, capture_output=True, text=True)
        end_ts = time.time()
        log.info("[%s] %d/%d 회차 종료 (rc=%d, %.1fs)",
                 name, i, repeat, proc.returncode, end_ts - start_ts)

        if proc.returncode != 0:
            # threshold 미달 시 k6는 rc=99로 끝남 — knee point 탐지에선 정상 상황이라 경고만
            log.warning("[%s] %d/%d k6 비정상 종료 — stderr 일부: %s",
                        name, i, repeat, (proc.stderr or "")[-300:])

        summary = {}
        if summary_path.exists():
            try:
                summary = _extract_metrics(json.loads(summary_path.read_text(encoding="utf-8")))
            except json.JSONDecodeError:
                log.error("[%s] %d/%d summary JSON 파싱 실패", name, i, repeat)

        results.append(RunResult(
            scenario=name, run_index=i, start_ts=start_ts, end_ts=end_ts,
            summary=summary, raw_summary_path=str(summary_path),
            return_code=proc.returncode,
        ))

    return results
