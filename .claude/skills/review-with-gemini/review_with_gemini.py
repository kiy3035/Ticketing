#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
review_with_gemini.py — 현재 Claude Code 대화나 특정 PR을 Google Gemini에게 보내 비평을 받는다.

흐름:
  1) 현재 프로젝트의 최신 transcript(.jsonl)를 자동 탐지해 읽기 좋은 텍스트로 변환
     (또는 --pr 로 특정 PR의 본문+diff)
  2) API 키/토큰/비밀번호/JWT/PEM 등 비밀값을 정규식으로 마스킹
  3) "Claude가 놓친 것·잘못된 접근·더 나은 대안을 날카롭게 지적하라"는 프롬프트로 Gemini 호출
  4) 비평을 stdout(+선택적 파일)으로 출력

Gemini의 OpenAI 호환 엔드포인트를 사용한다(요청/응답이 OpenAI 형식과 동일).
의존성 없음(표준 라이브러리 urllib만 사용). API 키는 환경변수에서만 읽는다(커밋 금지).

환경변수:
  GEMINI_API_KEY    (필수) Google AI Studio에서 무료 발급한 Gemini API 키
                    (https://aistudio.google.com/apikey)
  GEMINI_MODEL      (선택) 모델명. 기본 gemini-2.5-flash-lite
  GEMINI_BASE_URL   (선택) 기본 https://generativelanguage.googleapis.com/v1beta/openai

사용 예:
  python review_with_gemini.py                         # 최신 세션 자동 + Gemini 호출
  python review_with_gemini.py --pr 9                   # PR #9 비평
  python review_with_gemini.py --dry-run --out out.md  # 전송할 내용만 파일로(호출 안 함)
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request
import urllib.error

# Windows 콘솔(cp949)에서도 이모지/한글이 깨지지 않도록 UTF-8 강제
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

# Gemini의 OpenAI 호환 엔드포인트 (요청/응답이 OpenAI chat/completions 형식과 동일)
DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
# 무료 티어가 있고 할당량이 가장 넉넉한 모델(2026-06 기준 1,500 req/day).
# 주의: gemini-2.0-flash/1.5-flash는 2026-03-03자로 retire됨 → 사용 금지.
# 품질이 더 필요하면 --model gemini-2.5-flash (무료 티어 있으나 할당량 적음).
DEFAULT_MODEL = "gemini-2.5-flash-lite"


# ──────────────────────────────────────────────────────────────────────────
# 1) transcript 위치 탐지
# ──────────────────────────────────────────────────────────────────────────
def default_transcript_dir() -> str:
    """현재 작업 디렉토리(cwd)로부터 Claude Code transcript 폴더 경로를 도출한다.
    Claude Code는 cwd 절대경로의 영숫자 외 문자를 '-'로 치환한 폴더에 세션을 저장한다.
    예) C:\\ws\\ticketing → ~/.claude/projects/C--ws-ticketing
    """
    cwd = os.path.abspath(os.getcwd())
    encoded = re.sub(r"[^a-zA-Z0-9]", "-", cwd)
    return os.path.join(os.path.expanduser("~"), ".claude", "projects", encoded)


def find_latest_transcript(transcript_dir: str) -> str:
    """폴더 내 가장 최근에 수정된 .jsonl(= 현재 세션)을 반환한다."""
    if not os.path.isdir(transcript_dir):
        raise FileNotFoundError(f"transcript 폴더를 찾지 못함: {transcript_dir}")
    files = [
        os.path.join(transcript_dir, f)
        for f in os.listdir(transcript_dir)
        if f.endswith(".jsonl")
    ]
    if not files:
        raise FileNotFoundError(f".jsonl 파일이 없음: {transcript_dir}")
    return max(files, key=os.path.getmtime)


# ──────────────────────────────────────────────────────────────────────────
# 2) JSONL → 읽기 좋은 텍스트
# ──────────────────────────────────────────────────────────────────────────
def _block_to_text(block: dict, tool_result_limit: int) -> str:
    """assistant/user content의 블록 1개를 텍스트로 변환."""
    btype = block.get("type")
    if btype == "text":
        return block.get("text", "")
    if btype == "tool_use":
        name = block.get("name", "?")
        raw = json.dumps(block.get("input", {}), ensure_ascii=False)
        if len(raw) > 600:
            raw = raw[:600] + " …(생략)"
        return f"[도구 호출: {name}] {raw}"
    if btype == "tool_result":
        content = block.get("content", "")
        if isinstance(content, list):
            content = " ".join(
                c.get("text", "") for c in content if isinstance(c, dict)
            )
        content = str(content)
        if len(content) > tool_result_limit:
            content = content[:tool_result_limit] + f" …(결과 {len(content)}자 중 일부)"
        return f"[도구 결과] {content}"
    # thinking 등 나머지는 비평 노이즈라 생략
    return ""


def transcript_to_text(path: str, tool_result_limit: int = 800) -> str:
    """transcript JSONL을 사람이 읽는 대화 텍스트로 변환."""
    lines_out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            if d.get("type") not in ("user", "assistant"):
                continue
            msg = d.get("message") or {}
            role = msg.get("role", d.get("type"))
            content = msg.get("content")
            if isinstance(content, str):
                text = content
            elif isinstance(content, list):
                parts = [_block_to_text(b, tool_result_limit) for b in content if isinstance(b, dict)]
                text = "\n".join(p for p in parts if p)
            else:
                text = ""
            text = text.strip()
            if not text:
                continue
            label = "사용자" if role == "user" else "Claude"
            lines_out.append(f"### {label}\n{text}")
    return "\n\n".join(lines_out)


# ──────────────────────────────────────────────────────────────────────────
# 3) 비밀값 마스킹
# ──────────────────────────────────────────────────────────────────────────
def _mask_assignment(m: re.Match) -> str:
    return f"{m.group(1)}=[MASKED]"


# (정규식, 치환) 목록 — 외부 전송 전 비밀값 제거
MASK_PATTERNS = [
    # PEM 블록 (가장 먼저, DOTALL)
    (re.compile(r"-----BEGIN [^-]+-----.*?-----END [^-]+-----", re.DOTALL), "[MASKED:PEM]"),
    # Google(Gemini) API 키
    (re.compile(r"AIza[0-9A-Za-z_\-]{35}"), "[MASKED:GOOGLE]"),
    # Anthropic / OpenAI 키 (대화에 섞일 수 있어 함께 마스킹)
    (re.compile(r"sk-ant-[A-Za-z0-9_\-]{20,}"), "[MASKED:KEY]"),
    (re.compile(r"sk-[A-Za-z0-9_\-]{20,}"), "[MASKED:KEY]"),
    # GitHub 토큰
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"), "[MASKED:GH]"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{20,}"), "[MASKED:GH]"),
    # AWS access key id
    (re.compile(r"AKIA[0-9A-Z]{16}"), "[MASKED:AWS]"),
    # JWT (header.payload.signature)
    (re.compile(r"eyJ[A-Za-z0-9_\-]+\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+"), "[MASKED:JWT]"),
    # Bearer 토큰
    (re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]{20,}"), "Bearer [MASKED]"),
    # key=value 형태의 비밀값 (값만 가림)
    (re.compile(
        r"(?i)\b(password|passwd|secret|token|api[_-]?key|access[_-]?key|client[_-]?secret)\s*[=:]\s*['\"]?[^\s'\"]{6,}"
    ), _mask_assignment),
]


def mask_secrets(text: str) -> tuple[str, int]:
    """비밀값을 마스킹하고 (마스킹된 텍스트, 마스킹 건수)를 반환."""
    count = 0
    for pattern, repl in MASK_PATTERNS:
        text, n = pattern.subn(repl, text)
        count += n
    return text, count


# ──────────────────────────────────────────────────────────────────────────
# PR 모드: gh로 PR 본문+diff 가져오기
# ──────────────────────────────────────────────────────────────────────────
def _gh(*args: str) -> str:
    """gh CLI 실행. GH_BIN 환경변수로 경로 지정 가능(기본 'gh')."""
    gh = os.environ.get("GH_BIN", "gh")
    try:
        out = subprocess.run(
            [gh, *args], capture_output=True, text=True, encoding="utf-8",
        )
    except FileNotFoundError as e:
        raise RuntimeError(
            "gh(GitHub CLI)를 찾지 못했습니다. PATH에 추가하거나 "
            "GH_BIN 환경변수로 경로를 지정하세요."
        ) from e
    if out.returncode != 0:
        raise RuntimeError(f"gh 오류: {out.stderr.strip() or out.stdout.strip()}")
    return out.stdout


def build_pr_text(pr: str, diff_limit: int) -> str:
    """PR 번호/URL로 본문+변경파일+diff를 읽기 좋은 텍스트로 만든다."""
    meta_raw = _gh(
        "pr", "view", pr, "--json",
        "number,title,author,body,baseRefName,headRefName,additions,deletions,changedFiles,files",
    )
    m = json.loads(meta_raw)
    author = (m.get("author") or {}).get("login", "?")
    files = "\n".join(
        f"  - {f.get('path')} (+{f.get('additions', 0)}/-{f.get('deletions', 0)})"
        for f in (m.get("files") or [])
    )
    diff = _gh("pr", "diff", pr)
    if len(diff) > diff_limit:
        diff = diff[:diff_limit] + f"\n…(diff {len(diff)}자 중 일부 생략)"
    return (
        f"# PR #{m.get('number')} — {m.get('title')}\n"
        f"작성자: {author} | {m.get('headRefName')} → {m.get('baseRefName')} | "
        f"+{m.get('additions',0)}/-{m.get('deletions',0)}, {m.get('changedFiles',0)} files\n\n"
        f"## 본문\n{m.get('body') or '(본문 없음)'}\n\n"
        f"## 변경 파일\n{files or '(없음)'}\n\n"
        f"## Diff\n```diff\n{diff}\n```\n"
    )


# ──────────────────────────────────────────────────────────────────────────
# 4) Gemini 호출
# ──────────────────────────────────────────────────────────────────────────
SYSTEM_PROMPT_CONV = (
    "너는 까다롭고 경험 많은 시니어 백엔드 엔지니어다. "
    "지금부터 다른 AI(Claude Code)와 사용자가 나눈 작업 대화 기록을 보게 된다. "
    "Claude의 편을 들지 말고, 제3자 검토자로서 냉정하게 평가하라. "
    "다음을 한국어로, 근거와 함께 날카롭게 지적하라:\n"
    "1) Claude가 놓치거나 빠뜨린 것\n"
    "2) 잘못됐거나 위험한 접근/판단\n"
    "3) 더 나은 대안 (구체적으로)\n"
    "4) 사실관계가 의심스러운 주장(과장·미검증)\n"
    "잘한 점은 짧게만 언급하고, 개선점에 집중하라. 막연한 칭찬은 금지. "
    "확신이 없는 지적은 '추정'이라고 표시하라."
)

SYSTEM_PROMPT_PR = (
    "너는 까다롭고 경험 많은 시니어 백엔드 엔지니어다. "
    "지금부터 GitHub Pull Request의 본문과 diff를 보게 된다. 냉정하게 코드리뷰하라. "
    "다음을 한국어로, 근거(파일·라인 단위면 더 좋음)와 함께 날카롭게 지적하라:\n"
    "1) 버그·엣지케이스·동시성/트랜잭션 문제\n"
    "2) 보안 취약점(비밀값 노출, 인젝션, 권한 등)\n"
    "3) 성능·자원 누수 우려\n"
    "4) 설계/가독성 개선점과 더 나은 대안\n"
    "5) PR 본문 설명과 실제 diff의 불일치, 과장·미검증 주장\n"
    "잘한 점은 짧게만. 막연한 칭찬 금지. 확신 없는 지적은 '추정'이라 표시하라."
)


def call_gemini(text: str, model: str, system_prompt: str, user_prefix: str) -> str:
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError(
            "GEMINI_API_KEY 환경변수가 없습니다. "
            "https://aistudio.google.com/apikey 에서 무료 발급 후 "
            "PowerShell: $env:GEMINI_API_KEY=\"AIza...\" 로 설정하고 다시 실행하세요."
        )
    base = os.environ.get("GEMINI_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    url = f"{base}/chat/completions"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prefix + "\n\n" + text},
        ],
        "temperature": 0.4,
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise RuntimeError(f"Gemini API 오류 {e.code}: {body}") from e
    return data["choices"][0]["message"]["content"]


# ──────────────────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description="현재 Claude 대화나 PR을 Gemini에게 보내 비평받기")
    ap.add_argument("--transcript", help="transcript .jsonl 경로(기본: 현재 프로젝트 최신 세션)")
    ap.add_argument("--pr", help="PR 번호/URL — 지정 시 대화 대신 해당 PR(본문+diff)을 비평")
    ap.add_argument("--model", default=os.environ.get("GEMINI_MODEL", DEFAULT_MODEL),
                    help=f"Gemini 모델 (기본 {DEFAULT_MODEL} 또는 GEMINI_MODEL)")
    ap.add_argument("--max-chars", type=int, default=240000,
                    help="Gemini에 보낼 최대 문자 수(초과 시 최근 대화 위주로 자름). 기본 240000")
    ap.add_argument("--out", help="비평/전송내용을 저장할 파일 경로")
    ap.add_argument("--dry-run", action="store_true",
                    help="API 호출 없이 전송할 내용만 출력/저장(전송 전 검토용)")
    ap.add_argument("--no-mask", action="store_true", help="비밀값 마스킹 비활성화(권장 안 함)")
    args = ap.parse_args()

    # 1) 비평 대상 확보 — PR 모드 vs 대화(transcript) 모드
    if args.pr:
        print(f"[i] PR 모드: #{args.pr}", file=sys.stderr)
        text = build_pr_text(args.pr, diff_limit=args.max_chars)
        system_prompt = SYSTEM_PROMPT_PR
        user_prefix = f"다음은 리뷰할 GitHub Pull Request(#{args.pr})다."
    else:
        path = args.transcript or find_latest_transcript(default_transcript_dir())
        print(f"[i] transcript: {path}", file=sys.stderr)
        text = transcript_to_text(path)
        system_prompt = SYSTEM_PROMPT_CONV
        user_prefix = "다음은 Claude Code와의 작업 대화 기록이다."

    # 2) 마스킹
    if not args.no_mask:
        text, masked = mask_secrets(text)
        print(f"[i] 비밀값 마스킹: {masked}건", file=sys.stderr)

    # 3) 길이 budget (초과 시 최근 대화 우선)
    if len(text) > args.max_chars:
        text = "[앞부분 생략 — 최근 대화 위주로 전송]\n\n" + text[-args.max_chars:]
        print(f"[i] 길이 초과로 최근 {args.max_chars}자만 전송", file=sys.stderr)

    if args.dry_run:
        out = "# (DRY-RUN) Gemini에 전송될 내용\n\n" + text
        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(out)
            print(f"[i] dry-run 내용 저장: {args.out}", file=sys.stderr)
        else:
            print(out)
        return 0

    # 4) Gemini 호출
    print(f"[i] Gemini 호출 중 (model={args.model}) …", file=sys.stderr)
    critique = call_gemini(text, args.model, system_prompt, user_prefix)

    header = f"# 🔍 외부 AI(Gemini, {args.model}) 비평\n\n"
    result = header + critique
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(result)
        print(f"[i] 비평 저장: {args.out}", file=sys.stderr)
    print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
