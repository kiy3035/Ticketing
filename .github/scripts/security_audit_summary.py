"""
주간 보안 점검 요약 — Trivy 스캔 결과(trivy.json)를 읽어 정리하고,
HIGH/CRITICAL 의존성 취약점이 있으면 GitHub Issue로 보고한다. (스케줄 cron 용)

- ANTHROPIC_API_KEY 있으면 Claude가 우선순위·요약을 보태고, 없으면 표만 게시(graceful).
- 취약점 0건이면 이슈를 만들지 않고 로그만 남긴다(주간 노이즈 방지).

환경변수: GITHUB_TOKEN, REPO("owner/repo"), (선택) ANTHROPIC_API_KEY, MODEL
"""
import datetime
import json
import os
import sys

import requests

GITHUB_API = "https://api.github.com"
TRIVY_JSON = "trivy.json"


def load_findings(path: str) -> list[dict]:
    """Trivy JSON에서 취약점만 평탄화."""
    if not os.path.exists(path):
        return []
    raw = open(path, encoding="utf-8").read().strip()
    if not raw:
        return []
    data = json.loads(raw)
    out = []
    for res in data.get("Results", []) or []:
        for v in res.get("Vulnerabilities", []) or []:
            out.append({
                "severity": v.get("Severity", "UNKNOWN"),
                "pkg": v.get("PkgName"),
                "installed": v.get("InstalledVersion"),
                "fixed": v.get("FixedVersion") or "-",
                "id": v.get("VulnerabilityID"),
                "title": (v.get("Title") or "")[:110],
            })
    return out


def to_table(findings: list[dict]) -> str:
    order = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3}
    rows = sorted(findings, key=lambda d: order.get(d["severity"], 9))
    lines = ["| 심각도 | 패키지 | 설치 | 수정본 | 취약점 | 설명 |",
             "|--------|--------|------|--------|--------|------|"]
    for x in rows:
        lines.append(f"| {x['severity']} | {x['pkg']} | {x['installed']} | "
                     f"{x['fixed']} | {x['id']} | {x['title']} |")
    return "\n".join(lines)


def ai_summary(table: str) -> str | None:
    """키 있으면 Claude로 우선순위 요약, 없으면 None."""
    if not os.environ.get("ANTHROPIC_API_KEY"):
        return None
    try:
        from anthropic import Anthropic
        client = Anthropic()
        resp = client.messages.create(
            model=os.environ.get("MODEL", "claude-opus-4-8"),
            max_tokens=1500,
            system=("너는 보안 엔지니어다. 의존성 취약점 목록을 한국어로 우선순위화해 요약하라. "
                    "수정본이 있는 것부터 무엇을 먼저 올려야 하는지, 실제 악용 위험도를 간결히. "
                    "근거 없는 과장 금지."),
            messages=[{"role": "user", "content": f"취약점 표:\n{table}\n\n우선순위 요약을 작성하라."}],
        )
        return "".join(b.text for b in resp.content if b.type == "text")
    except Exception as e:  # API 오류로 리포트 전체가 죽지 않게
        return f"_AI 요약 실패: {e} (원본 표 참고)_"


def create_issue(repo: str, token: str, title: str, body: str) -> str:
    resp = requests.post(
        f"{GITHUB_API}/repos/{repo}/issues",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
        json={"title": title, "body": body, "labels": ["security"]},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json().get("html_url", "")


def main():
    findings = load_findings(TRIVY_JSON)
    iso = datetime.date.today().isocalendar()
    if not findings:
        print(f"[{iso[0]}-W{iso[1]:02d}] HIGH/CRITICAL 취약점 없음 — 이슈 생성 생략")
        return

    table = to_table(findings)
    summary = ai_summary(table)
    body = [f"## 주간 의존성 취약점 점검 ({iso[0]}-W{iso[1]:02d})",
            f"\nTrivy 스캔 결과 **{len(findings)}건**(HIGH/CRITICAL) 발견.\n",
            "> 자동 생성 리포트입니다. AI 요약은 보조이며 근거는 아래 원본 표입니다.\n"]
    if summary:
        body.append("### AI 우선순위 요약\n" + summary + "\n")
    body.append("### 원본 목록 (Trivy)\n" + table)

    url = create_issue(os.environ["REPO"], os.environ["GITHUB_TOKEN"],
                       f"주간 보안 점검 {iso[0]}-W{iso[1]:02d}", "\n".join(body))
    print("이슈 생성:", url)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"보안 점검 요약 실패: {e}", file=sys.stderr)
        sys.exit(1)
