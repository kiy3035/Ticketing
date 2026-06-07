#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
review_with_ai.py — 현재 Claude Code 대화를 외부 AI(OpenAI/GPT)에게 보내 비평을 받는다.

흐름:
  1) 현재 프로젝트의 최신 transcript(.jsonl)를 자동 탐지해 읽기 좋은 텍스트로 변환
  2) API 키/토큰/비밀번호/JWT/PEM 등 비밀값을 정규식으로 마스킹
  3) "Claude가 놓친 것·잘못된 접근·더 나은 대안을 날카롭게 지적하라"는 프롬프트로 GPT 호출
  4) 비평을 stdout(+선택적 파일)으로 출력

의존성 없음(표준 라이브러리 urllib만 사용). API 키는 환경변수에서만 읽는다(커밋 금지).

환경변수:
  OPENAI_API_KEY        (필수) OpenAI API 키
  OPENAI_REVIEW_MODEL   (선택) 모델명. 기본 gpt-4o
  OPENAI_BASE_URL       (선택) 기본 https://api.openai.com/v1

사용 예:
  python review_with_ai.py                         # 최신 세션 자동 + GPT 호출
  python review_with_ai.py --dry-run --out out.md  # 전송할 내용만 파일로(호출 안 함)
  python review_with_ai.py --transcript path.jsonl --model gpt-4o
"""
import argparse
import json
import os
import re
import sys
import urllib.request
import urllib.error

# Windows 콘솔(cp949)에서도 이모지/한글이 깨지지 않도록 UTF-8 강제
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass


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
    # Anthropic / OpenAI 키
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
# 4) OpenAI 호출
# ──────────────────────────────────────────────────────────────────────────
SYSTEM_PROMPT = (
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


def call_openai(text: str, model: str) -> str:
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError(
            "OPENAI_API_KEY 환경변수가 없습니다. "
            "PowerShell: $env:OPENAI_API_KEY=\"sk-...\" 로 설정 후 다시 실행하세요."
        )
    base = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    url = f"{base}/chat/completions"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": "다음은 Claude Code와의 작업 대화 기록이다.\n\n" + text},
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
        raise RuntimeError(f"OpenAI API 오류 {e.code}: {body}") from e
    return data["choices"][0]["message"]["content"]


# ──────────────────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description="현재 Claude 대화를 GPT에게 보내 비평받기")
    ap.add_argument("--transcript", help="transcript .jsonl 경로(기본: 현재 프로젝트 최신 세션)")
    ap.add_argument("--model", default=os.environ.get("OPENAI_REVIEW_MODEL", "gpt-4o"),
                    help="OpenAI 모델 (기본 gpt-4o 또는 OPENAI_REVIEW_MODEL)")
    ap.add_argument("--max-chars", type=int, default=240000,
                    help="GPT에 보낼 최대 문자 수(초과 시 최근 대화 위주로 자름). 기본 240000")
    ap.add_argument("--out", help="비평/전송내용을 저장할 파일 경로")
    ap.add_argument("--dry-run", action="store_true",
                    help="API 호출 없이 전송할 내용만 출력/저장(전송 전 검토용)")
    ap.add_argument("--no-mask", action="store_true", help="비밀값 마스킹 비활성화(권장 안 함)")
    args = ap.parse_args()

    # 1) transcript 확보
    path = args.transcript or find_latest_transcript(default_transcript_dir())
    print(f"[i] transcript: {path}", file=sys.stderr)
    text = transcript_to_text(path)

    # 2) 마스킹
    if not args.no_mask:
        text, masked = mask_secrets(text)
        print(f"[i] 비밀값 마스킹: {masked}건", file=sys.stderr)

    # 3) 길이 budget (초과 시 최근 대화 우선)
    if len(text) > args.max_chars:
        text = "[앞부분 생략 — 최근 대화 위주로 전송]\n\n" + text[-args.max_chars:]
        print(f"[i] 길이 초과로 최근 {args.max_chars}자만 전송", file=sys.stderr)

    if args.dry_run:
        out = "# (DRY-RUN) GPT에 전송될 내용\n\n" + text
        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(out)
            print(f"[i] dry-run 내용 저장: {args.out}", file=sys.stderr)
        else:
            print(out)
        return 0

    # 4) GPT 호출
    print(f"[i] GPT 호출 중 (model={args.model}) …", file=sys.stderr)
    critique = call_openai(text, args.model)

    header = f"# 🔍 외부 AI(GPT, {args.model}) 비평\n\n"
    result = header + critique
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(result)
        print(f"[i] 비평 저장: {args.out}", file=sys.stderr)
    print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
