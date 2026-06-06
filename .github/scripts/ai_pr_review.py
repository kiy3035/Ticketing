"""
AI PR 리뷰 봇 — PR diff를 Claude에 보내 코드 리뷰를 생성하고 PR 코멘트로 게시한다.
GitHub Actions에서 pull_request 이벤트로 자동 실행된다.

환경변수:
  ANTHROPIC_API_KEY : Claude API 키 (GH secret)
  GITHUB_TOKEN      : 코멘트 게시용 (Actions가 자동 제공)
  REPO              : "owner/repo"
  PR_NUMBER         : PR 번호
  MODEL             : (선택) 모델 ID. 기본 claude-opus-4-8. 비용 절감 시 claude-sonnet-4-6 등
"""
import os
import sys

import requests
from anthropic import Anthropic

GITHUB_API = "https://api.github.com"
# diff가 너무 크면 토큰 한도/비용 문제 → 상한 두고 잘라서 보냄 (잘리면 명시)
MAX_DIFF_CHARS = 40000
MODEL = os.environ.get("MODEL", "claude-opus-4-8")

_SYSTEM = """너는 시니어 백엔드 엔지니어다. GitHub PR의 변경 diff를 리뷰한다.
한국어 마크다운으로, 실질적이고 실행 가능한 리뷰를 작성하라.

발견한 이슈는 빠짐없이 보고하되 각각 심각도와 확신도를 함께 표기한다(중요도로 미리
거르지 마라 — 필터링은 사람이 한다). 다음 우선순위로 본다:
1) 정확성 버그 (동시성/락, 트랜잭션, NPE, 경계조건)
2) 보안 (인증/인가, 인젝션, 비밀값 노출)
3) 성능 (불필요한 쿼리/락 경합)
4) 단순화·가독성 (가벼운 제안)

근거 없는 추측은 피하고, 파일/위치를 가능한 한 명시하라.
출력 구조:
## 요약
## 🔴 정확성·버그
## 🔒 보안
## ⚡ 성능
## 🧹 개선 제안
각 항목: `- [심각도/확신도] 파일:위치 — 내용`. 해당 없으면 "해당 없음"."""


def get_pr_diff(repo: str, pr_number: str, token: str) -> str:
    """GitHub API로 PR diff 텍스트를 가져온다."""
    resp = requests.get(
        f"{GITHUB_API}/repos/{repo}/pulls/{pr_number}",
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github.v3.diff"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.text


def post_comment(repo: str, pr_number: str, token: str, body: str):
    """PR(이슈) 코멘트로 리뷰 게시."""
    resp = requests.post(
        f"{GITHUB_API}/repos/{repo}/issues/{pr_number}/comments",
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json"},
        json={"body": body},
        timeout=30,
    )
    resp.raise_for_status()


def review_diff(diff: str) -> str:
    """Claude에 diff를 보내 리뷰 텍스트를 받는다."""
    truncated = len(diff) > MAX_DIFF_CHARS
    if truncated:
        diff = diff[:MAX_DIFF_CHARS]

    note = "\n\n⚠️ diff가 커서 일부만 검토했습니다.\n" if truncated else ""
    user_prompt = f"다음 PR diff를 리뷰하라:\n\n```diff\n{diff}\n```{note}"

    client = Anthropic()  # ANTHROPIC_API_KEY 자동 사용
    # Opus 4.8: temperature/top_p/budget_tokens 미사용(전달 시 400). 비스트리밍 + max_tokens 16K 이하 권장
    resp = client.messages.create(
        model=MODEL,
        max_tokens=8000,
        system=_SYSTEM,
        messages=[{"role": "user", "content": user_prompt}],
    )
    # thinking 블록 등이 섞일 수 있으므로 text 블록만 합쳐 추출
    return "".join(b.text for b in resp.content if b.type == "text")


def main():
    token = os.environ["GITHUB_TOKEN"]
    repo = os.environ["REPO"]
    pr_number = os.environ["PR_NUMBER"]

    diff = get_pr_diff(repo, pr_number, token)
    if not diff.strip():
        print("diff가 비어 있음 — 스킵")
        return

    review = review_diff(diff)
    body = (f"## 🤖 AI 코드 리뷰 ({MODEL})\n\n"
            f"> 참고용 1차 리뷰입니다. 최종 판단은 리뷰어가 합니다.\n\n"
            f"{review}\n")
    post_comment(repo, pr_number, token, body)
    print("리뷰 코멘트 게시 완료")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"AI PR 리뷰 실패: {e}", file=sys.stderr)
        sys.exit(1)
