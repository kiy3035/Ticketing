"""
튜닝 어드바이저 — 부하 테스트 결과를 보고 '구조화된' 튜닝 제안(JSON)을 만든다.

기존 analyzer.py는 진단을 '자유 텍스트'로 냈다(사람이 읽고 수동 적용). 이 모듈은 그 끊긴
지점을 잇는다: 같은 메트릭을 받아 **적용 가능한 파라미터 변경 제안**으로 구조화한다.

설계(가드레일 — 면접에서 설명할 핵심):
  1. AI는 '제안'만 한다. 적용 가능한 파라미터는 config.tuning.params 화이트리스트뿐.
  2. 화이트리스트 밖 제안 / 범위 밖 값 / 변화 없는 제안은 **코드가** 거부·clamp 한다.
     → parse_proposals / sanitize 는 순수 함수라 pytest로 결정론 검증된다(AI 신뢰 안 함).
  3. 기본은 dry-run(제안서 출력)뿐. 실제 적용·재실행·채택 판정은 다음 단계(tuning_loop) 몫.

환경변수: analyzer.py와 동일(GEMINI_API_KEY / GEMINI_MODEL / GEMINI_BASE_URL).
키가 없으면 AI 호출은 건너뛰고, 화이트리스트·범위 정보만 담은 안내 리포트를 낸다(graceful).
"""
import argparse
import json
import logging
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

import yaml

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

log = logging.getLogger("harness.advisor")

HARNESS_DIR = Path(__file__).resolve().parent
DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
DEFAULT_MODEL = "gemini-2.5-flash"

# 프롬프트: 반드시 '아래 JSON 스키마로만' 답하도록 강제한다. 자유 서술을 막아야
# 코드가 파싱·검증할 수 있다(자율 루프의 전제).
_SYSTEM = """너는 백엔드 성능 엔지니어다. 티켓팅 시스템 부하 테스트 결과를 보고
'커넥션 풀' 튜닝 제안을 만든다. 반드시 아래 JSON만 출력하라(설명·마크다운·코드펜스 금지).

{{"proposals": [{{"env": "<화이트리스트의 env 이름 그대로>", "proposed": <정수>,
  "rationale": "<수치 근거 한 줄>", "expected_effect": "<기대 효과 한 줄>",
  "confidence": "low|medium|high"}}]}}

규칙:
- env 는 반드시 아래 '튜닝 가능 파라미터'에 있는 이름만 쓴다. 그 외는 절대 만들지 마라.
- 한 번에 1개 파라미터만 제안한다(원인 격리). 바꿀 이유가 없으면 proposals를 빈 배열로.
- proposed 는 정수. 근거는 제시된 메트릭에서 읽히는 사실만(예: hikari_pending이 높으면 풀 고갈)."""


def _build_user_prompt(metrics: dict, params: dict) -> str:
    """메트릭 + 화이트리스트(현재값·범위)를 프롬프트 텍스트로 직렬화."""
    lines = [f"# 시나리오: {metrics.get('scenario', '?')}", "",
             "## 헤드라인 메트릭",
             f"- RPS: {metrics.get('rps')}",
             f"- p95(ms): {metrics.get('p95_ms')}",
             f"- error_rate: {metrics.get('error_rate')}",
             "", "## 서버측 신호 (max / mean)"]
    for name, stat in (metrics.get("signals") or {}).items():
        if isinstance(stat, dict):
            lines.append(f"- {name}: max={stat.get('max')}, mean={stat.get('mean')}")
        else:
            lines.append(f"- {name}: {stat}")
    lines += ["", "## 튜닝 가능 파라미터 (이 env 이름만 사용)"]
    for env, spec in params.items():
        lines.append(f"- {env}: 현재={spec.get('current')}, 허용 {spec.get('min')}~{spec.get('max')}"
                     f" — {spec.get('desc', '')}")
    return "\n".join(lines)


# ──────────────────────────────────────────────────────────────────────────
# 핵심: 파싱 + 검증 (순수 함수 — pytest 대상. AI 응답을 신뢰하지 않는 방어선)
# ──────────────────────────────────────────────────────────────────────────
def parse_proposals(text: str) -> list:
    """AI 응답 텍스트에서 proposals 배열을 추출. 코드펜스/잡텍스트에 견고하게.

    실패하면 빈 리스트(파이프라인이 죽지 않게). 형식 위반도 빈 리스트로 흡수.
    """
    if not text:
        return []
    s = text.strip()
    # ```json ... ``` 펜스 제거
    if s.startswith("```"):
        s = s.split("```", 2)[1] if s.count("```") >= 2 else s.strip("`")
        if s.lstrip().startswith("json"):
            s = s.lstrip()[4:]
    # 본문에 잡설이 섞여도 첫 '{' ~ 마지막 '}'만 취한다
    lo, hi = s.find("{"), s.rfind("}")
    if lo == -1 or hi == -1 or hi < lo:
        return []
    try:
        obj = json.loads(s[lo:hi + 1])
    except json.JSONDecodeError:
        return []
    props = obj.get("proposals") if isinstance(obj, dict) else None
    return props if isinstance(props, list) else []


def _clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


def sanitize(proposals: list, params: dict) -> tuple:
    """제안을 화이트리스트·범위로 걸러 (accepted, rejected) 로 나눈다.

    - env 가 화이트리스트에 없으면 거부.
    - proposed 가 정수로 해석 불가면 거부.
    - 범위 밖이면 clamp(잘라내고 표시) — AI가 9999를 내도 무력화.
    - clamp 후 current 와 같으면 '변화 없음'으로 거부.
    """
    accepted, rejected = [], []
    for p in proposals:
        if not isinstance(p, dict):
            rejected.append({"raw": p, "reason": "형식 오류(객체 아님)"})
            continue
        env = p.get("env")
        spec = params.get(env)
        if spec is None:
            rejected.append({"env": env, "reason": "화이트리스트에 없는 파라미터"})
            continue
        try:
            proposed = int(p.get("proposed"))
        except (TypeError, ValueError):
            rejected.append({"env": env, "reason": f"정수 아님: {p.get('proposed')!r}"})
            continue
        lo, hi, cur = int(spec["min"]), int(spec["max"]), int(spec["current"])
        clamped = _clamp(proposed, lo, hi)
        if clamped == cur:
            rejected.append({"env": env, "reason": f"현재값과 동일({cur}) — 변화 없음"})
            continue
        accepted.append({
            "env": env, "current": cur, "proposed": clamped,
            "clamped_from": proposed if clamped != proposed else None,
            "range": [lo, hi],
            "rationale": p.get("rationale", ""),
            "expected_effect": p.get("expected_effect", ""),
            "confidence": p.get("confidence", "n/a"),
        })
    return accepted, rejected


def render_md(scenario: str, accepted: list, rejected: list, ai_skipped: bool) -> str:
    """dry-run 제안 리포트(마크다운). 적용 명령(env)을 함께 제시하되 실행하지는 않는다."""
    lines = [f"## 🔧 AI 튜닝 제안 (dry-run) — {scenario}", ""]
    if ai_skipped:
        lines.append("> ⚠️ GEMINI_API_KEY 미설정 — AI 제안을 건너뜀. 화이트리스트만 표시.")
        lines.append("")
    if not accepted and not rejected:
        lines.append("_제안 없음(AI가 변경 불필요로 판단했거나 응답이 비었음)._")
        return "\n".join(lines)
    if accepted:
        lines += ["### ✅ 채택 후보 (사람 검증 후 적용)",
                  "| 파라미터(env) | 현재 | 제안 | 범위 | 확신도 | 근거 |",
                  "|------|------|------|------|--------|------|"]
        for a in accepted:
            note = f" (⚠️clamp←{a['clamped_from']})" if a["clamped_from"] is not None else ""
            lines.append(f"| `{a['env']}` | {a['current']} | **{a['proposed']}**{note} "
                         f"| {a['range'][0]}~{a['range'][1]} | {a['confidence']} | {a['rationale']} |")
        lines += ["", "#### 적용 명령(예시 — 검증 후 수동 실행)", "```bash"]
        for a in accepted:
            lines.append(f"export {a['env']}={a['proposed']}   # 그 뒤 앱 재기동 → 재측정 → regression_gate 전후 비교")
        lines.append("```")
    if rejected:
        lines += ["", "### 🚫 코드가 거부한 제안 (가드레일 동작)",
                  "| 제안 | 거부 사유 |", "|------|----------|"]
        for r in rejected:
            lines.append(f"| {r.get('env', r.get('raw'))} | {r['reason']} |")
    lines += ["", "> 이 리포트는 **제안서**다. 실제 적용·재실행·채택 판정은 사람이 승인한 뒤 수행한다."]
    return "\n".join(lines)


# ──────────────────────────────────────────────────────────────────────────
def _call_gemini(system: str, user: str, model: str, max_tokens: int) -> str:
    base = os.environ.get("GEMINI_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    payload = {"model": model,
               "messages": [{"role": "system", "content": system},
                            {"role": "user", "content": user}],
               "max_tokens": max_tokens, "temperature": 0.2}
    req = urllib.request.Request(
        f"{base}/chat/completions", data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {os.environ['GEMINI_API_KEY']}",
                 "Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def advise(metrics: dict, cfg: dict) -> str:
    """메트릭 → dry-run 제안 리포트(마크다운). 키 없거나 오류여도 graceful."""
    params = cfg.get("params") or {}
    scenario = metrics.get("scenario", "?")
    if not cfg.get("enabled", True):
        return f"## 🔧 AI 튜닝 제안 — {scenario}\n\n_튜닝 어드바이저 비활성화(config.tuning.enabled=false)._"
    if not os.environ.get("GEMINI_API_KEY"):
        return render_md(scenario, [], [], ai_skipped=True)
    model = os.environ.get("GEMINI_MODEL") or cfg.get("model") or DEFAULT_MODEL
    prompt = _build_user_prompt(metrics, params)
    log.info("[%s] 튜닝 제안 요청 (model=%s)", scenario, model)
    try:
        raw = _call_gemini(_SYSTEM, prompt, model, int(cfg.get("max_tokens", 4000)))
    except (urllib.error.URLError, KeyError, TimeoutError) as e:
        log.error("[%s] Gemini 호출 실패: %s", scenario, e)
        return f"## 🔧 AI 튜닝 제안 — {scenario}\n\n_AI 호출 실패: {e}. 화이트리스트를 참고해 수동 검토._"
    accepted, rejected = sanitize(parse_proposals(raw), params)
    return render_md(scenario, accepted, rejected, ai_skipped=False)


def _load_yaml(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f)


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s",
                        datefmt="%H:%M:%S")
    ap = argparse.ArgumentParser(description="AI 튜닝 어드바이저 (dry-run 제안)")
    ap.add_argument("--metrics", required=True, help="메트릭 JSON 경로 (헤드라인+signals)")
    ap.add_argument("--config", default=str(HARNESS_DIR / "config.yaml"))
    args = ap.parse_args()

    metrics = json.loads(Path(args.metrics).read_text(encoding="utf-8"))
    cfg = _load_yaml(Path(args.config)).get("tuning", {})
    print(advise(metrics, cfg))
    return 0


if __name__ == "__main__":
    sys.exit(main())
