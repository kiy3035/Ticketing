"""
ablation 스윕 — 한 파라미터의 여러 후보값을 훑어 '설정 축의 knee point(최적값)'를 찾는다.

Step 2(tuning_loop)가 제안값 1개를 전후 검증했다면, 여기선 여러 값을 스윕해
"몇이 최적인가 + 어디부터 수확체감인가"를 곡선으로 보여준다. 프로젝트 핵심 주제인
knee point/bottleneck 분석을 '설정값 축'에 그대로 적용한 것.

가드레일(Step 2와 동일):
- 적용은 env override 실험뿐 — 소스/커밋 안 건드림. 스윕 끝나면 항상 baseline으로 원복.
- 스윕 대상은 화이트리스트(tuning.params)에 있는 파라미터만. error_cap 초과 후보는 기각.
- 최적 선택(pick_optimum)은 순수 함수 — pytest로 결정론 검증. 실제 측정만 앱/AWS 필요.
- 최적값을 찾아도 '소스 영구 반영'은 사람이 증거(곡선) 보고 지시 — 도구 밖.
"""
import argparse
import json
import logging
import sys
from datetime import datetime
from pathlib import Path

import matplotlib
import yaml

matplotlib.use("Agg")  # GUI 없는 서버/CI에서 렌더 (reporter.py와 동일 컨벤션)
import matplotlib.pyplot as plt  # noqa: E402

import tuning_loop  # noqa: E402  (apply_param/run_load 재활용)

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

log = logging.getLogger("harness.ablation")
HARNESS_DIR = Path(__file__).resolve().parent


# ──────────────────────────────────────────────────────────────────────────
# 핵심: 최적값 선택 + knee 탐지 (순수 함수 — pytest 대상)
# ──────────────────────────────────────────────────────────────────────────
def _detect_knee(sorted_valid: list, min_gain_pct: float) -> int:
    """value 오름차순에서 직전 대비 rps 증가율이 min_gain_pct 미만으로 떨어지는 첫 지점의
    '직전 값'을 sweet spot으로 본다(더 키워도 이득이 미미해지는 지점). 끝까지 증가하면 마지막 값."""
    for prev, cur in zip(sorted_valid, sorted_valid[1:]):
        gain = (cur["rps"] - prev["rps"]) / prev["rps"] * 100 if prev["rps"] else 0.0
        if gain < min_gain_pct:
            return prev["value"]
    return sorted_valid[-1]["value"]


def pick_optimum(results: list, error_cap: float, knee_min_gain_pct: float = 2.0) -> dict:
    """후보 결과에서 최적값을 고른다.

    results: [{value, rps, p95_ms, error_rate}, ...]
    - error_rate > error_cap 후보는 기각(가드).
    - 통과 후보 중 rps 최대를 best. knee(수확체감 지점)는 별도로 표시.
    반환: {best, valid, rejected, knee}
    """
    valid, rejected = [], []
    for r in results:
        if isinstance(r.get("rps"), (int, float)) and r.get("error_rate", 1.0) <= error_cap:
            valid.append(r)
        else:
            rejected.append(r)
    if not valid:
        return {"best": None, "valid": [], "rejected": rejected, "knee": None}
    best = max(valid, key=lambda r: r["rps"])
    knee = _detect_knee(sorted(valid, key=lambda r: r["value"]), knee_min_gain_pct)
    return {"best": best, "valid": valid, "rejected": rejected, "knee": knee}


def render_sweep_md(param: str, baseline_value, results: list, picked: dict) -> str:
    """스윕 곡선 표 + 최적/수확체감 권고. 소스 반영은 사람 몫임을 명시."""
    lines = [f"## 📈 Ablation 스윕 — `{param}`", "",
             "| 후보값 | RPS | p95(ms) | error_rate | 판정 |",
             "|--------|-----|---------|-----------|------|"]
    valid_vals = {r["value"] for r in picked["valid"]}
    best_val = picked["best"]["value"] if picked["best"] else None
    for r in sorted(results, key=lambda x: x["value"]):
        v = r["value"]
        tag = "🥇 최적" if v == best_val else ("✅" if v in valid_vals else "🚫 기각(error)")
        base = " (baseline)" if v == baseline_value else ""
        lines.append(f"| {v}{base} | {r.get('rps')} | {r.get('p95_ms')} | "
                     f"{r.get('error_rate')} | {tag} |")
    lines.append("")
    if picked["best"]:
        b = picked["best"]
        lines.append(f"> 🥇 **최적값 = {b['value']}** (RPS {b['rps']}, p95 {b['p95_ms']}ms). "
                     f"수확체감 시작 ≈ **{picked['knee']}** 부근 — 그 이상은 이득이 미미하거나 악화.")
        if best_val != baseline_value:
            lines.append(f">\n> 소스 반영을 원하면 `application.properties`의 해당 값을 "
                         f"{b['value']}로 바꾸도록 지시하세요(이 도구는 소스를 건드리지 않음).")
    else:
        lines.append("> ⚠️ 모든 후보가 error_cap을 초과해 기각됨 — baseline 유지.")
    return "\n".join(lines)


def render_chart(param: str, results: list, picked: dict, out_path: Path):
    """후보값 vs RPS(좌)·p95(우) 곡선. 최적값을 수직선으로 표시. 라벨은 영문(폰트 안전)."""
    rs = sorted([r for r in results if isinstance(r.get("rps"), (int, float))],
                key=lambda r: r["value"])
    if not rs:
        return None
    xs = [r["value"] for r in rs]
    rps = [r["rps"] for r in rs]
    p95 = [r.get("p95_ms") for r in rs]

    fig, ax1 = plt.subplots(figsize=(9, 4))
    ax1.plot(xs, rps, "o-", color="tab:blue", linewidth=1.8, label="RPS")
    ax1.set_xlabel("candidate value")
    ax1.set_ylabel("RPS", color="tab:blue")
    ax1.tick_params(axis="y", labelcolor="tab:blue")
    ax2 = ax1.twinx()
    ax2.plot(xs, p95, "s--", color="tab:red", linewidth=1.4, label="p95 (ms)")
    ax2.set_ylabel("p95 (ms)", color="tab:red")
    ax2.tick_params(axis="y", labelcolor="tab:red")
    if picked.get("best"):
        ax1.axvline(picked["best"]["value"], color="tab:green", linestyle=":",
                    linewidth=2, label=f"optimum={picked['best']['value']}")
    ax1.set_title(f"Ablation sweep: {param}")
    ax1.grid(True, alpha=0.3)
    ax1.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(out_path, dpi=110)
    plt.close(fig)
    return out_path


# ──────────────────────────────────────────────────────────────────────────
# I/O 파트 (앱·k6 필요 — AWS 실측에서 검증)
# ──────────────────────────────────────────────────────────────────────────
def run_sweep(scenario: str, param: str, values: list, baseline_value,
              repeat, harness_dir: Path, apply_cfg: dict) -> list:
    """후보값마다 env 적용 → 부하 측정 → 수집. 끝나면 baseline으로 원복(소스 불가침)."""
    results = []
    try:
        for v in values:
            tuning_loop.apply_param(param, v, apply_cfg)
            m = tuning_loop.run_load(scenario, repeat, harness_dir)
            results.append({"value": v, "rps": m.get("rps"),
                            "p95_ms": m.get("p95_ms"), "error_rate": m.get("error_rate")})
            log.info("후보 %s=%s → rps=%s p95=%s err=%s", param, v,
                     m.get("rps"), m.get("p95_ms"), m.get("error_rate"))
    finally:
        try:
            tuning_loop.apply_param(param, baseline_value, apply_cfg)
            log.info("스윕 종료 — baseline %s=%s 로 원복", param, baseline_value)
        except Exception as e:  # noqa: BLE001
            log.error("원복 실패(수동 확인 필요): %s", e)
    return results


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s",
                        datefmt="%H:%M:%S")
    ap = argparse.ArgumentParser(description="Ablation 스윕 — 설정값 축의 최적점 탐색")
    ap.add_argument("--scenario", required=True)
    ap.add_argument("--config", default=str(HARNESS_DIR / "config.yaml"))
    ap.add_argument("--repeat", type=int)
    ap.add_argument("--results", help="실측을 건너뛰고 이 결과 JSON으로 리포트/차트만 생성(데모)")
    ap.add_argument("--out", help="리포트/차트 출력 디렉토리(기본: reports/sweep_<ts>)")
    args = ap.parse_args()

    tuning_cfg = yaml.safe_load(Path(args.config).read_text(encoding="utf-8")).get("tuning", {})
    sweep = tuning_cfg.get("sweep", {})
    params = tuning_cfg.get("params", {})
    param = sweep.get("param")
    if param not in params:   # 가드: 화이트리스트 밖 파라미터는 스윕 불가
        print(f"[오류] 스윕 대상 '{param}'이 tuning.params 화이트리스트에 없음", file=sys.stderr)
        return 1
    baseline_value = params[param]["current"]
    error_cap = sweep.get("error_cap", 0.05)
    knee_gain = sweep.get("knee_min_gain_pct", 2.0)

    if args.results:
        results = json.loads(Path(args.results).read_text(encoding="utf-8"))
    else:
        results = run_sweep(args.scenario, param, sweep.get("values", []),
                            baseline_value, args.repeat, HARNESS_DIR, tuning_cfg.get("apply", {}))

    picked = pick_optimum(results, error_cap, knee_gain)

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = Path(args.out) if args.out else HARNESS_DIR / "reports" / f"sweep_{ts}"
    out_dir.mkdir(parents=True, exist_ok=True)
    chart = render_chart(param, results, picked, out_dir / "sweep.png")
    md = render_sweep_md(param, baseline_value, results, picked)
    if chart:
        md += f"\n\n![sweep]({chart.name})"
    (out_dir / "sweep.md").write_text(md, encoding="utf-8")
    print(md)
    print(f"\n[i] 산출물: {out_dir}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
