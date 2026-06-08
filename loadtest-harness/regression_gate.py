#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
regression_gate.py — 부하 테스트 성능 회귀 게이트.

run.py가 남긴 집계 메트릭(summary.json)을 기준선(baseline)과 비교해,
p95/RPS/에러율이 임계 이상 퇴행하면 실패(exit 1)시킨다. CI에 붙이면
"성능이 나빠진 변경"을 머지 전에 자동 차단하는 게이트가 된다.

설계: 비교 로직(evaluate)은 순수 함수라 단위 테스트로 검증된다. AI는 보조 —
회귀가 잡혔을 때만 `--explain`으로 Gemini가 원인 가설을 1차 제시(없어도 동작).

사용:
  python regression_gate.py --baseline baselines/knee-point.json
  python regression_gate.py --baseline baselines/knee-point.json --current reports/<ts>/summary.json --explain
"""
import argparse
import json
import os
import sys
import urllib.request
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

HARNESS_DIR = Path(__file__).resolve().parent
DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
DEFAULT_MODEL = "gemini-2.5-flash"


# ──────────────────────────────────────────────────────────────────────────
# 핵심: 비교 로직 (순수 함수 — 테스트 대상)
# ──────────────────────────────────────────────────────────────────────────
def evaluate(current: dict, baseline: dict) -> dict:
    """현재 메트릭을 기준선과 비교해 회귀 여부를 판정.

    baseline 형식:
      {"metrics": {"<name>": {"baseline": float, "direction": "higher_better"|"lower_better",
                              "max_regression_pct": float}  # 또는 "max_abs_increase": float
                  }}
    반환: {"passed": bool, "rows": [{name, baseline, current, change, threshold, status}]}
    """
    rows = []
    passed = True
    for name, spec in baseline.get("metrics", {}).items():
        cur = current.get(name)
        base = spec.get("baseline")
        if not isinstance(cur, (int, float)) or not isinstance(base, (int, float)):
            rows.append({"name": name, "baseline": base, "current": cur,
                         "change": "n/a", "threshold": "-", "status": "SKIP"})
            continue
        direction = spec.get("direction", "lower_better")
        regressed = False
        # 절대 증가 한도(에러율 등): 0 기준 % 변화가 무의미할 때
        if "max_abs_increase" in spec:
            limit = spec["max_abs_increase"]
            delta = cur - base
            threshold = f"+{limit:g} 이내"
            change = f"{delta:+.4f}"
            regressed = delta > limit
        else:
            pct = spec.get("max_regression_pct", 10)
            change_pct = (cur - base) / base * 100 if base else 0.0
            change = f"{change_pct:+.1f}%"
            if direction == "higher_better":     # RPS 등: 떨어지면 회귀
                threshold = f"-{pct:g}% 이내"
                regressed = change_pct < -pct
            else:                                # p95 등: 오르면 회귀
                threshold = f"+{pct:g}% 이내"
                regressed = change_pct > pct
        status = "REGRESS" if regressed else "PASS"
        if regressed:
            passed = False
        rows.append({"name": name, "baseline": base, "current": cur,
                     "change": change, "threshold": threshold, "status": status})
    return {"passed": passed, "rows": rows}


def render_markdown(result: dict, scenario: str) -> str:
    icon = "✅ 통과" if result["passed"] else "🔴 성능 회귀 감지"
    lines = [f"## 🚦 성능 회귀 게이트 — {scenario}: {icon}", "",
             "| 지표 | 기준선 | 이번 | 변화 | 허용 | 판정 |",
             "|------|--------|------|------|------|------|"]
    for r in result["rows"]:
        b = f"{r['baseline']:g}" if isinstance(r["baseline"], (int, float)) else r["baseline"]
        c = f"{r['current']:g}" if isinstance(r["current"], (int, float)) else r["current"]
        mark = {"PASS": "✅", "REGRESS": "🔴", "SKIP": "⚪"}[r["status"]]
        lines.append(f"| {r['name']} | {b} | {c} | {r['change']} | {r['threshold']} | {mark} {r['status']} |")
    if not result["passed"]:
        lines.append("\n> 🔴 기준선 대비 임계 이상 퇴행. 원인 확인 전 머지 보류 권장.")
    return "\n".join(lines)


# ──────────────────────────────────────────────────────────────────────────
def find_latest_summary(reports_dir: Path) -> Path:
    """reports/ 아래 가장 최근 summary.json 반환."""
    cands = sorted(reports_dir.glob("*/summary.json"), key=lambda p: p.stat().st_mtime)
    if not cands:
        raise FileNotFoundError(f"summary.json을 찾지 못함: {reports_dir}")
    return cands[-1]


def explain_with_gemini(result: dict, scenario: str, model: str) -> str:
    """회귀가 잡혔을 때 Gemini가 원인 가설 1차 제시(보조). 키 없으면 건너뜀."""
    if not os.environ.get("GEMINI_API_KEY"):
        return ""
    base = os.environ.get("GEMINI_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    table = "\n".join(
        f"- {r['name']}: 기준 {r['baseline']} → 이번 {r['current']} ({r['change']}) [{r['status']}]"
        for r in result["rows"]
    )
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "너는 백엔드 성능 엔지니어다. 부하 테스트 회귀 결과를 보고 "
             "가장 가능성 있는 원인 가설 2~3개와 확인 방법을 한국어로 간단히 제시하라. 추정은 추정이라 표시."},
            {"role": "user", "content": f"시나리오 {scenario} 회귀:\n{table}"},
        ],
        "max_tokens": 4000, "temperature": 0.2,
    }
    req = urllib.request.Request(
        f"{base}/chat/completions", data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {os.environ['GEMINI_API_KEY']}",
                 "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return "\n\n### 🤖 AI 원인 가설 (추정)\n" + data["choices"][0]["message"]["content"]
    except Exception as e:  # noqa: BLE001
        return f"\n\n_(AI 원인 분석 실패: {e})_"


def main() -> int:
    ap = argparse.ArgumentParser(description="부하 테스트 성능 회귀 게이트")
    ap.add_argument("--baseline", required=True, help="기준선 JSON 경로")
    ap.add_argument("--current", help="이번 측정 summary.json (기본: reports/ 최신)")
    ap.add_argument("--reports-dir", default=str(HARNESS_DIR / "reports"))
    ap.add_argument("--explain", action="store_true", help="회귀 시 Gemini 원인 가설 추가")
    ap.add_argument("--model", default=os.environ.get("GEMINI_MODEL", DEFAULT_MODEL))
    args = ap.parse_args()

    baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
    cur_path = Path(args.current) if args.current else find_latest_summary(Path(args.reports_dir))
    current = json.loads(cur_path.read_text(encoding="utf-8"))
    scenario = current.get("scenario") or baseline.get("scenario") or "?"

    result = evaluate(current, baseline)
    md = render_markdown(result, scenario)
    if not result["passed"] and args.explain:
        md += explain_with_gemini(result, scenario, args.model)
    print(md)
    print(f"\n[i] current={cur_path}", file=sys.stderr)
    return 0 if result["passed"] else 1   # 회귀 시 exit 1 → CI 차단


if __name__ == "__main__":
    sys.exit(main())
