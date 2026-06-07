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
import logging
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
    for scenario in scenarios:
        log.info("===== 시나리오 시작: %s =====", scenario["name"])
        runs = k6_runner.run_scenario(scenario, k6_cfg, HARNESS_DIR)

        # 마지막 스크랩이 Prometheus에 반영되도록 잠깐 대기 후 회차별 수집
        time.sleep(settle)
        prom_per_run = []
        for r in runs:
            log.info("[%s] %d회차 Prometheus 수집 (%.0f~%.0f)",
                     scenario["name"], r.run_index, r.start_ts, r.end_ts)
            prom_per_run.append(collector.collect(r.start_ts, r.end_ts))

        ai = analyzer.analyze(scenario["name"], runs, prom_per_run, cfg["analyzer"])
        report_path = reporter.write_report(
            scenario["name"], runs, prom_per_run, ai, cfg["report"], HARNESS_DIR
        )
        reports.append(report_path)
        log.info("===== 시나리오 완료: %s =====", scenario["name"])

    print("\n생성된 리포트:")
    for p in reports:
        print(f"  - {p}")


if __name__ == "__main__":
    main()
