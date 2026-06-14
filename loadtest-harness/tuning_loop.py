"""
튜닝 루프 — 자율 성능 튜닝 폐루프의 오케스트레이터.

흐름:
  BEFORE 측정 → advisor 구조화 제안 → (--apply 시) 제안을 env로 '실험적' 적용 →
  AFTER 재측정 → decide()로 전후 비교 채택/롤백 판정 → 실험 원복 → 리포트.

가드레일(핵심 — 면접에서 설명):
  - AI는 제안만. 적용은 화이트리스트(advisor)·범위 clamp를 통과한 1개 파라미터뿐(단일 변수 격리).
  - 적용은 **env override**로만 — 소스 파일/커밋은 절대 건드리지 않는다. 실험 후 항상 원복.
  - 채택 판정(decide)은 순수 함수 + regression_gate 재활용 → 결정론적. AI 자기평가 안 믿음.
  - 기본은 dry-run(제안만). 실제 적용은 --apply + config.tuning.apply.restart_command 필요.
  - 영구 반영(소스 수정)은 이 도구 밖 — 사람이 증거를 보고 결정한다.
"""
import argparse
import json
import logging
import os
import subprocess
import sys
import time
from pathlib import Path

import yaml

import advisor
import regression_gate

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

log = logging.getLogger("harness.tuning")
HARNESS_DIR = Path(__file__).resolve().parent


# ──────────────────────────────────────────────────────────────────────────
# 핵심: 전후 판정 (순수 함수 — pytest 대상. regression_gate 재활용)
# ──────────────────────────────────────────────────────────────────────────
def _pct(cur, base) -> float:
    return (cur - base) / base * 100 if base else 0.0


def _before_baseline(before: dict, th: dict) -> dict:
    """BEFORE 메트릭을 regression_gate가 먹는 baseline 스펙으로 변환(악화 판정 재활용)."""
    pct = th.get("regress_pct", 5.0)
    return {"metrics": {
        "rps": {"baseline": before.get("rps"), "direction": "higher_better", "max_regression_pct": pct},
        "p95_ms": {"baseline": before.get("p95_ms"), "direction": "lower_better", "max_regression_pct": pct},
        "error_rate": {"baseline": before.get("error_rate"), "direction": "lower_better",
                       "max_abs_increase": th.get("error_abs", 0.01)},
    }}


def decide(before: dict, after: dict, th: dict) -> dict:
    """전후 비교 → ADOPT / ROLLBACK / NEUTRAL.

    - 어떤 지표든 임계 이상 악화(regression_gate가 감지) → ROLLBACK
    - 악화 없고 rps↑ 또는 p95↓ 가 improve_pct 이상 → ADOPT
    - 그 외(변화 미미) → NEUTRAL
    """
    reg = regression_gate.evaluate(after, _before_baseline(before, th))
    rps_d = _pct(after.get("rps", 0), before.get("rps", 0))
    p95_d = _pct(after.get("p95_ms", 0), before.get("p95_ms", 0))
    improve = th.get("improve_pct", 5.0)
    improved = rps_d >= improve or p95_d <= -improve
    if not reg["passed"]:
        verdict = "ROLLBACK"
    elif improved:
        verdict = "ADOPT"
    else:
        verdict = "NEUTRAL"
    return {"verdict": verdict, "rps_change_pct": round(rps_d, 1),
            "p95_change_pct": round(p95_d, 1), "regression": reg}


def render_decision_md(target: dict, before: dict, after: dict, decision: dict) -> str:
    """전후 증거 + 판정 리포트. 채택해도 '소스 반영은 사람'임을 명시."""
    icon = {"ADOPT": "✅ 채택 권장", "ROLLBACK": "🔴 롤백(악화)", "NEUTRAL": "⚪ 중립(변화 미미)"}[decision["verdict"]]
    lines = [f"## 🔁 튜닝 루프 결과 — {before.get('scenario', '?')}: {icon}", "",
             f"**실험 파라미터**: `{target['env']}` {target['current']} → **{target['proposed']}** "
             f"(확신도 {target.get('confidence', 'n/a')})", "",
             "| 지표 | BEFORE | AFTER | 변화 |",
             "|------|--------|-------|------|",
             f"| RPS | {before.get('rps')} | {after.get('rps')} | {decision['rps_change_pct']:+.1f}% |",
             f"| p95(ms) | {before.get('p95_ms')} | {after.get('p95_ms')} | {decision['p95_change_pct']:+.1f}% |",
             f"| error_rate | {before.get('error_rate')} | {after.get('error_rate')} | "
             f"{after.get('error_rate', 0) - before.get('error_rate', 0):+.4f} |", ""]
    if decision["verdict"] == "ADOPT":
        lines.append(f"> ✅ 실측상 개선 확인. **소스 반영을 원하면** `application.properties`의 해당 값을 "
                     f"{target['proposed']}로 바꾸도록 지시하세요(이 도구는 소스를 건드리지 않음).")
    elif decision["verdict"] == "ROLLBACK":
        lines.append("> 🔴 기준 이상 악화 — 제안 기각. 실험값은 자동 원복됨.")
    else:
        lines.append("> ⚪ 유의미한 개선 없음 — 현재값 유지. 실험값은 자동 원복됨.")
    return "\n".join(lines)


# ──────────────────────────────────────────────────────────────────────────
# I/O 파트 (앱·k6 필요 — AWS 실측에서 검증)
# ──────────────────────────────────────────────────────────────────────────
def run_load(scenario: str, repeat, harness_dir: Path) -> dict:
    """run.py를 서브프로세스로 1세트 실행하고 최신 metrics.json을 읽어 반환(재활용)."""
    cmd = [sys.executable, str(harness_dir / "run.py"), "--scenario", scenario, "--no-analyze"]
    if repeat:
        cmd += ["--repeat", str(repeat)]
    log.info("부하 측정 실행: %s", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd=str(harness_dir))
    cands = sorted((harness_dir / "reports").glob("*/metrics.json"), key=lambda p: p.stat().st_mtime)
    if not cands:
        raise FileNotFoundError("metrics.json을 찾지 못함 — run.py 산출 확인")
    return json.loads(cands[-1].read_text(encoding="utf-8"))


def apply_param(env: str, value, apply_cfg: dict) -> None:
    """제안을 '실험적'으로 적용: env를 주입한 환경에서 외부화된 재기동 명령 실행.

    소스/커밋은 절대 안 건드린다. restart_command가 비면 적용 불가(dry-run만 가능).
    """
    restart = (apply_cfg or {}).get("restart_command", "")
    if not restart:
        raise RuntimeError("config.tuning.apply.restart_command 미설정 — --apply 불가(dry-run만 가능)")
    child_env = {**os.environ, env: str(value)}
    cmd = restart.format(env=env, value=value)
    log.info("실험 적용: %s=%s → 재기동", env, value)
    subprocess.run(cmd, shell=True, check=True, env=child_env)
    time.sleep(int((apply_cfg or {}).get("warmup_seconds", 20)))


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s",
                        datefmt="%H:%M:%S")
    ap = argparse.ArgumentParser(description="자율 성능 튜닝 루프")
    ap.add_argument("--scenario", required=True, help="시나리오 이름(config의 k6.scenarios)")
    ap.add_argument("--config", default=str(HARNESS_DIR / "config.yaml"))
    ap.add_argument("--repeat", type=int, help="회차 override")
    ap.add_argument("--apply", action="store_true",
                    help="제안을 실험적으로 적용·재측정해 전후 판정(기본: dry-run 제안만)")
    ap.add_argument("--before-metrics", help="BEFORE 측정을 건너뛰고 이 metrics.json 사용(데모/디버그)")
    args = ap.parse_args()

    tuning_cfg = yaml.safe_load(Path(args.config).read_text(encoding="utf-8")).get("tuning", {})

    # 1) BEFORE 측정 (또는 주어진 메트릭 사용)
    if args.before_metrics:
        before = json.loads(Path(args.before_metrics).read_text(encoding="utf-8"))
    else:
        before = run_load(args.scenario, args.repeat, HARNESS_DIR)

    # 2) 구조화 제안 (항상 출력)
    prop = advisor.propose(before, tuning_cfg)
    print(advisor.render_md(prop["scenario"], prop["accepted"], prop["rejected"], prop["ai_skipped"]))

    if not args.apply:
        print("\n_(dry-run — 적용하려면 --apply. 적용은 env override 실험일 뿐 소스는 안 건드림.)_")
        return 0
    if not prop["accepted"]:
        print("\n_(적용할 채택 후보 없음 — 종료.)_")
        return 0

    # 3) 단일 변수 격리: 채택 후보 1개만 실험
    target = prop["accepted"][0]
    apply_cfg = tuning_cfg.get("apply", {})
    try:
        apply_param(target["env"], target["proposed"], apply_cfg)
        after = run_load(args.scenario, args.repeat, HARNESS_DIR)
        decision = decide(before, after, tuning_cfg.get("decide", {}))
        print("\n" + render_decision_md(target, before, after, decision))
    finally:
        # 4) 실험 종료 — 항상 원복(소스 반영은 사람 몫)
        try:
            apply_param(target["env"], target["current"], apply_cfg)
            log.info("실험 원복: %s=%s 복구", target["env"], target["current"])
        except Exception as e:  # noqa: BLE001
            log.error("원복 실패(수동 확인 필요): %s", e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
