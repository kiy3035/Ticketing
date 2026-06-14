"""
부하 테스트 자동화 하네스 — 오케스트레이터.

흐름: k6 N회 실행 → 회차별 Prometheus 메트릭 수집 → Claude 보조 분석 → 리포트 생성.
사람이 하던 '설정 변경 → 3회 실행 → Grafana 스크린샷 → 평균 계산 → md 작성'을
명령 한 번으로 대체한다.

사용:
  python run.py                          # config의 모든 시나리오, 기본 설정
  python run.py --scenario knee-point    # 특정 시나리오만
  python run.py --repeat 1 --no-analyze  # 빠른 점검 (1회, AI 분석 생략)
"""
import argparse
import json
import logging
import statistics
import sys
import time
from pathlib import Path

import yaml

import analyzer
import k6_runner
import reporter
from prometheus_collector import PrometheusCollector

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("harness")

HARNESS_DIR = Path(__file__).resolve().parent


def _hot(seq: list) -> list:
    """JVM cold(run1) 제외 — 회차 2개 이상일 때만 hot 회차를 쓴다(수기 방법론과 동일)."""
    return seq[1:] if len(seq) > 1 else seq


def _agg_k6(runs: list, key: str):
    """k6 회차 summary에서 key를 회차 평균으로 집계(숫자만)."""
    vals = [r.summary.get(key) for r in runs if isinstance(r.summary.get(key), (int, float))]
    return round(statistics.fmean(vals), 4) if vals else None


def aggregate_signals(prom_runs: list) -> dict:
    """회차별 Prometheus 통계 리스트 → {name: {max, mean}} 집계.

    max는 회차별 max들의 max(최악값), mean은 회차별 mean들의 평균. 수집 실패(None/빈) 회차는 건너뛴다.
    advisor.py가 병목 신호(hikari_pending 등)로 읽는 입력이 된다.
    """
    names = set()
    for p in prom_runs:
        names.update(p.keys())
    out = {}
    for name in sorted(names):
        maxes = [p[name]["max"] for p in prom_runs
                 if isinstance(p.get(name, {}).get("max"), (int, float))]
        means = [p[name]["mean"] for p in prom_runs
                 if isinstance(p.get(name, {}).get("mean"), (int, float))]
        out[name] = {
            "max": round(max(maxes), 4) if maxes else None,
            "mean": round(statistics.fmean(means), 4) if means else None,
        }
    return out


def build_metrics(scenario: str, runs: list, prom_runs: list) -> dict:
    """advisor.py가 먹는 메트릭 구조(헤드라인 + signals)를 만든다. hot 회차만 사용."""
    hot_runs, hot_prom = _hot(runs), _hot(prom_runs)
    return {
        "scenario": scenario,
        "rps": _agg_k6(hot_runs, "http_reqs_rate"),
        "p95_ms": _agg_k6(hot_runs, "http_req_duration_p95"),
        "error_rate": _agg_k6(hot_runs, "http_req_failed_rate"),
        "signals": aggregate_signals(hot_prom),
    }


def _write_summary(scenario: str, runs: list, report_path: Path) -> None:
    """성능 회귀 게이트(regression_gate.py)가 비교할 집계 메트릭을 summary.json으로 저장.

    수기 방법론과 맞춰 run1(JVM cold)은 제외하고 hot 회차 평균을 쓴다(회차 2개 이상일 때).
    """
    hot = _hot(runs)
    summary = {
        "scenario": scenario,
        "runs_used": [r.run_index for r in hot],
        "rps": _agg_k6(hot, "http_reqs_rate"),
        "p95_ms": _agg_k6(hot, "http_req_duration_p95"),
        "error_rate": _agg_k6(hot, "http_req_failed_rate"),
    }
    (report_path.parent / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    log.info("[%s] 집계 메트릭 저장 → summary.json (회차 %s)", scenario, summary["runs_used"])


def _write_metrics(scenario: str, runs: list, prom_runs: list, report_path: Path) -> None:
    """튜닝 어드바이저(advisor.py)가 먹는 signals 포함 메트릭을 metrics.json으로 저장."""
    metrics = build_metrics(scenario, runs, prom_runs)
    (report_path.parent / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    log.info("[%s] 튜닝용 메트릭 저장 → metrics.json (signals %d개)",
             scenario, len(metrics["signals"]))


def load_config(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    parser = argparse.ArgumentParser(description="부하 테스트 자동화 하네스")
    parser.add_argument("--config", default=str(HARNESS_DIR / "config.yaml"))
    parser.add_argument("--scenario", help="실행할 시나리오 이름 (기본: 전체)")
    parser.add_argument("--repeat", type=int, help="회차 반복 횟수 override")
    parser.add_argument("--no-analyze", action="store_true", help="AI 분석 생략")
    args = parser.parse_args()

    cfg = load_config(Path(args.config))
    k6_cfg = cfg["k6"]
    if args.repeat:
        k6_cfg["repeat"] = args.repeat
    if args.no_analyze:
        cfg["analyzer"]["enabled"] = False

    scenarios = k6_cfg["scenarios"]
    if args.scenario:
        scenarios = [s for s in scenarios if s["name"] == args.scenario]
        if not scenarios:
            log.error("시나리오 '%s'를 config에서 찾을 수 없음", args.scenario)
            sys.exit(1)

    collector = PrometheusCollector(cfg["prometheus"])
    settle = int(cfg["prometheus"].get("settle_seconds", 5))

    reports = []
    failed = []
    # 시나리오 단위로 격리: 한 시나리오가 실패해도 나머지는 계속 진행한다.
    for scenario in scenarios:
        name = scenario["name"]
        log.info("===== 시나리오 시작: %s =====", name)
        try:
            runs = k6_runner.run_scenario(scenario, k6_cfg, HARNESS_DIR)

            # 마지막 스크랩이 Prometheus에 반영되도록 잠깐 대기 후 회차별 수집
            time.sleep(settle)
            prom_per_run = []
            for r in runs:
                log.info("[%s] %d회차 Prometheus 수집 (%.0f~%.0f)",
                         name, r.run_index, r.start_ts, r.end_ts)
                # 회차별 수집 실패가 시나리오 전체를 죽이지 않도록 격리 (빈 결과로 진행)
                try:
                    prom_per_run.append(collector.collect(r.start_ts, r.end_ts))
                except Exception as e:
                    log.error("[%s] %d회차 Prometheus 수집 실패 — 빈 결과로 진행: %s",
                              name, r.run_index, e)
                    prom_per_run.append({})

            ai = analyzer.analyze(name, runs, prom_per_run, cfg["analyzer"])
            report_path = reporter.write_report(
                name, runs, prom_per_run, ai, cfg["report"], HARNESS_DIR
            )
            reports.append(report_path)
            _write_summary(name, runs, report_path)   # 성능 회귀 게이트용 집계
            _write_metrics(name, runs, prom_per_run, report_path)  # 튜닝 어드바이저용 signals
            log.info("===== 시나리오 완료: %s =====", name)
        except Exception as e:
            log.error("===== 시나리오 실패, 건너뜀: %s — %s =====", name, e)
            failed.append(name)
            continue

    print("\n생성된 리포트:")
    for p in reports:
        print(f"  - {p}")
    if failed:
        print(f"\n실패해 건너뛴 시나리오 ({len(failed)}): {', '.join(failed)}")
        sys.exit(1)  # 일부라도 실패하면 CI가 알 수 있도록 비정상 종료


if __name__ == "__main__":
    main()
