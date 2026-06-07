#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
domain_guard.py — 이 프로젝트의 '핵심 불변식'을 PR diff에서 검사하는 도메인 특화 가드레일.

범용 AI 코드리뷰와 달리, CLAUDE.md의 절대 규칙(이 프로젝트만의 아키텍처 불변식)을 강제한다:
  1) 비밀값 커밋 금지            — 결정적(정규식)  · CRITICAL
  2) 임계치/타임아웃 하드코딩 금지 — 결정적(Java 휴리스틱) · WARNING
  3) 락 → 트랜잭션 순서 준수      — AI 보조(Gemini, 확신도 표시)
  4) @Transactional 범위 최소화   — AI 보조(Gemini, 확신도 표시)

설계 원칙: 결정적 검사가 신뢰의 핵심, AI는 '1차 의견(추정)'. 최종 판단은 사람.
GEMINI_API_KEY가 없으면 AI 단계는 건너뛰고 결정적 검사만 수행한다(graceful).

사용:
  python domain_guard.py --pr 12              # gh로 PR diff 가져와 검사 → 마크다운 출력
  python domain_guard.py --pr 12 --comment    # 결과를 PR 코멘트로 게시
  python domain_guard.py --diff-file d.patch  # 로컬 diff 파일 검사(오프라인/테스트)

환경변수: GEMINI_API_KEY(선택), GEMINI_MODEL(기본 gemini-2.5-flash-lite),
          GEMINI_BASE_URL(기본 OpenAI 호환 엔드포인트), GH_BIN(기본 gh)
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
DEFAULT_MODEL = "gemini-2.5-flash-lite"


# ──────────────────────────────────────────────────────────────────────────
# diff 파싱 — 추가된 라인만 (file, line_no, text)
# ──────────────────────────────────────────────────────────────────────────
def parse_added_lines(diff_text: str) -> list[tuple[str, int, str]]:
    """unified diff에서 '추가된(+) 라인'만 (파일경로, 신규파일 라인번호, 내용)로 추출."""
    added: list[tuple[str, int, str]] = []
    cur_file = "?"
    new_lineno = 0
    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            cur_file = line[6:].strip()
            continue
        if line.startswith("@@"):
            # @@ -a,b +c,d @@ → 신규 파일 시작 라인 c
            m = re.search(r"\+(\d+)", line)
            new_lineno = int(m.group(1)) if m else 0
            continue
        if line.startswith("+") and not line.startswith("+++"):
            added.append((cur_file, new_lineno, line[1:]))
            new_lineno += 1
        elif not line.startswith("-"):
            # 컨텍스트 라인은 신규 라인번호 증가, 삭제(-)는 증가 안 함
            new_lineno += 1
    return added


# ──────────────────────────────────────────────────────────────────────────
# 규칙 1: 비밀값 (결정적, CRITICAL)
# ──────────────────────────────────────────────────────────────────────────
_SECRET_PATTERNS = [
    (re.compile(r"-----BEGIN [^-]+-----"), "PEM 키 블록"),
    (re.compile(r"AIza[0-9A-Za-z_\-]{35}"), "Google/Gemini API 키"),
    (re.compile(r"sk-(?:ant-)?[A-Za-z0-9_\-]{20,}"), "OpenAI/Anthropic API 키"),
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"), "GitHub 토큰"),
    (re.compile(r"AKIA[0-9A-Z]{16}"), "AWS Access Key"),
    (re.compile(r"(?i)(password|passwd|secret|token|api[_-]?key)\s*[=:]\s*['\"][^'\"]{6,}['\"]"),
     "비밀값 평문 대입"),
]


def check_secrets(added: list[tuple[str, int, str]]) -> list[dict]:
    findings = []
    for f, ln, text in added:
        # placeholder(${VAR}, env 참조)는 제외
        if "${" in text or "os.environ" in text or "System.getenv" in text:
            continue
        for pat, label in _SECRET_PATTERNS:
            if pat.search(text):
                findings.append({
                    "rule": "비밀값 커밋 금지", "severity": "CRITICAL", "confidence": "high",
                    "file": f, "line": ln, "why": f"{label} 의심 — 환경변수/secret으로 외부화",
                    "snippet": text.strip()[:120],
                })
                break
    return findings


# ──────────────────────────────────────────────────────────────────────────
# 규칙 2: 임계치/타임아웃 하드코딩 (결정적, Java 휴리스틱, WARNING)
# ──────────────────────────────────────────────────────────────────────────
_HARDCODE_PATTERNS = [
    (re.compile(r"Thread\.sleep\(\s*\d{3,}\s*\)"), "Thread.sleep 리터럴"),
    (re.compile(r"Duration\.of(?:Millis|Seconds|Minutes|Hours)\(\s*\d+\s*\)"), "Duration 리터럴"),
    (re.compile(r"\.expire\([^,]+,\s*\d+"), "Redis expire 리터럴 TTL"),
    (re.compile(r"(?i)(timeout|ttl|maxretur|max[_-]?retry|maxretries|maxpoll)\s*=\s*\d+"),
     "타임아웃/재시도 리터럴"),
]


def check_hardcode(added: list[tuple[str, int, str]]) -> list[dict]:
    findings = []
    for f, ln, text in added:
        if not f.endswith(".java"):
            continue
        if "@Value" in text or "properties" in text.lower():
            continue  # 이미 외부화 중
        for pat, label in _HARDCODE_PATTERNS:
            if pat.search(text):
                findings.append({
                    "rule": "임계치/설정값 하드코딩 금지", "severity": "WARNING", "confidence": "high",
                    "file": f, "line": ln, "why": f"{label} — application.properties/환경변수로 외부화 검토",
                    "snippet": text.strip()[:120],
                })
                break
    return findings


# ──────────────────────────────────────────────────────────────────────────
# 규칙 3·4: 락→트랜잭션 순서, @Transactional 범위 (AI 보조)
# ──────────────────────────────────────────────────────────────────────────
_AI_SYSTEM = (
    "너는 Spring/동시성에 밝은 시니어 리뷰어다. 주어진 Java diff에서 '딱 두 가지'만 본다:\n"
    "1) 락→트랜잭션 순서: 분산 락(예: Redis 락, lock/tryLock)을 @Transactional 메서드 '안에서' "
    "획득하거나, 트랜잭션이 락보다 먼저 시작되는 패턴. 규칙: 락을 먼저 획득한 뒤 트랜잭션을 시작해야 한다.\n"
    "2) @Transactional 범위 과대: 트랜잭션 안에서 외부 호출(HTTP/Redis/Kafka/sleep) 등 오래 걸리는 작업.\n"
    "JSON만 출력하라. 형식: {\"findings\":[{\"rule\":\"락→트랜잭션 순서\"|\"@Transactional 범위\","
    "\"severity\":\"WARNING\",\"confidence\":\"low\"|\"medium\"|\"high\",\"file\":\"\",\"why\":\"근거 한 줄\"}]}. "
    "확실하지 않으면 confidence를 low로. 해당 없으면 findings는 빈 배열."
)


def check_invariants_ai(diff_text: str, model: str) -> list[dict]:
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("[i] GEMINI_API_KEY 없음 — AI 불변식 검사 건너뜀(결정적 검사만)", file=sys.stderr)
        return []
    # Java 변경만 추려 전송(토큰 절약)
    java_only = "\n".join(
        ln for ln in diff_text.splitlines()
        if ln.startswith(("+", "@@", "diff --git", "+++")) and (".java" in ln or ln.startswith(("+", "@@")))
    )
    base = os.environ.get("GEMINI_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": _AI_SYSTEM},
            {"role": "user", "content": "다음 diff를 검사하라:\n\n" + java_only[:200000]},
        ],
        "temperature": 0.1,
    }
    req = urllib.request.Request(
        f"{base}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        content = data["choices"][0]["message"]["content"]
        content = re.sub(r"^```(?:json)?|```$", "", content.strip(), flags=re.MULTILINE).strip()
        parsed = json.loads(content)
        out = []
        for fd in parsed.get("findings", []):
            fd.setdefault("severity", "WARNING")
            fd.setdefault("confidence", "low")
            fd["ai"] = True
            out.append(fd)
        return out
    except (urllib.error.URLError, KeyError, json.JSONDecodeError) as e:
        print(f"[!] AI 검사 실패(건너뜀): {e}", file=sys.stderr)
        return []


# ──────────────────────────────────────────────────────────────────────────
def render_markdown(det: list[dict], ai: list[dict], model: str) -> str:
    lines = ["## 🛡️ 도메인 가드레일 — 프로젝트 불변식 검사", ""]
    crit = [f for f in det if f["severity"] == "CRITICAL"]
    if crit:
        lines.append(f"### 🔴 CRITICAL {len(crit)}건 (결정적 — 머지 전 반드시 확인)")
        for f in crit:
            lines.append(f"- **{f['rule']}** · `{f['file']}:{f['line']}` — {f['why']}")
            lines.append(f"  - `{f['snippet']}`")
        lines.append("")
    warn = [f for f in det if f["severity"] == "WARNING"]
    if warn:
        lines.append(f"### 🟡 WARNING {len(warn)}건 (결정적 — 외부화 검토)")
        for f in warn:
            lines.append(f"- **{f['rule']}** · `{f['file']}:{f['line']}` — {f['why']}")
        lines.append("")
    if ai:
        lines.append(f"### 🤖 AI 보조 의견 {len(ai)}건 ({model} — *추정, 최종 판단은 사람*)")
        for f in ai:
            loc = f.get("file") or "?"
            lines.append(f"- [{f.get('confidence','low')}] **{f.get('rule','?')}** · `{loc}` — {f.get('why','')}")
        lines.append("")
    if not (crit or warn or ai):
        lines.append("✅ 검사한 불변식 위반 없음(결정적 + AI). *AI는 보조 — 사람 리뷰 대체 아님.*")
    lines.append("\n> 검사 규칙: 비밀값 커밋 · 임계치 하드코딩 · 락→트랜잭션 순서 · @Transactional 범위 "
                 "(출처: CLAUDE.md 절대 규칙)")
    return "\n".join(lines)


def _gh(*args: str) -> str:
    gh = os.environ.get("GH_BIN", "gh")
    out = subprocess.run([gh, *args], capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise RuntimeError(f"gh 오류: {out.stderr.strip()}")
    return out.stdout


def main() -> int:
    ap = argparse.ArgumentParser(description="도메인 특화 AI 가드레일")
    ap.add_argument("--pr", help="PR 번호 — gh로 diff 수집")
    ap.add_argument("--diff-file", help="로컬 diff 파일 경로(오프라인/테스트)")
    ap.add_argument("--model", default=os.environ.get("GEMINI_MODEL", DEFAULT_MODEL))
    ap.add_argument("--comment", action="store_true", help="결과를 PR 코멘트로 게시(--pr 필요)")
    ap.add_argument("--no-ai", action="store_true", help="AI 단계 생략(결정적 검사만)")
    args = ap.parse_args()

    if args.diff_file:
        diff_text = open(args.diff_file, encoding="utf-8").read()
    elif args.pr:
        diff_text = _gh("pr", "diff", args.pr)
    else:
        diff_text = sys.stdin.read()

    added = parse_added_lines(diff_text)
    det = check_secrets(added) + check_hardcode(added)
    ai = [] if args.no_ai else check_invariants_ai(diff_text, args.model)

    report = render_markdown(det, ai, args.model)
    print(report)

    if args.comment and args.pr:
        _gh("pr", "comment", args.pr, "--body", report)
        print(f"\n[i] PR #{args.pr} 코멘트 게시 완료", file=sys.stderr)

    # CRITICAL이 있으면 비정상 종료(CI 빨간불) — 결정적 고신뢰 항목만 차단
    return 2 if any(f["severity"] == "CRITICAL" for f in det) else 0


if __name__ == "__main__":
    sys.exit(main())
