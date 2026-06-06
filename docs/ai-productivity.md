# AI 활용 생산성 · 자동화

이 프로젝트에서 **AI를 활용해 반복 작업을 자동화하고 개발 생산성을 높인** 내역을 정리한다.
포인트는 "AI가 알아서 다 했다"가 아니라, **반복되던 수작업을 파이프라인·도구로 자동화하고,
판단이 필요한 지점에 AI를 보조로 붙였다**는 것이다.

> 정직성 원칙: 아래 정량 수치 중 일부는 추정치이며 그렇게 표기한다. AI가 만든 산출물은
> 테스트·원본 데이터로 검증 가능하게 했고, AI 분석/리뷰는 항상 "1차 의견, 최종 판단은 사람"으로 둔다.

> 👉 **이 문서가 AI 자동화의 진입점이다.** AI 자동화는 한 폴더가 아니라 여러 곳에 나뉘어 있다.

## 구성 요소 — 어디를 보면 되나

| 구성 요소 | 위치 | 무엇 |
|-----------|------|------|
| 부하 테스트 하네스 | [`loadtest-harness/`](../loadtest-harness/) | k6→수집→AI 진단→리포트 파이프라인 + pytest |
| Claude Code 스킬 | [`.claude/skills/`](../.claude/skills/) | `loadtest-analyze` / `loadtest-compare` / `loadtest` / `commit` |
| AI PR 리뷰 봇 | [`.github/scripts/ai_pr_review.py`](../.github/scripts/ai_pr_review.py), [`ai-pr-review.yml`](../.github/workflows/ai-pr-review.yml) | PR diff 자동 코드 리뷰 |
| 주간 보안 점검(스케줄) | [`.github/scripts/security_audit_summary.py`](../.github/scripts/security_audit_summary.py), [`weekly-security-audit.yml`](../.github/workflows/weekly-security-audit.yml) | 매주 의존성 취약점 스캔(Trivy) → AI 요약 → Issue |
| MCP 연동 | [`.mcp.json`](../.mcp.json) | GitHub MCP server — Claude가 이슈/PR/Actions 직접 조회·조작 |
| CI | [`.github/workflows/`](../.github/workflows/) | 하네스 pytest 자동, 배포 |
| 직접 돌려보는 데모 | [`docs/ai-automation-demo.md`](ai-automation-demo.md) | 재현 명령 + 실제 출력 예시 |

> 즉 `loadtest-harness/`만 읽으면 하네스 1개만 이해된다. 전체 그림은 이 문서 → 위 표의 링크 순으로 본다.

---

## 1. 부하 테스트 자동화 하네스 (`loadtest-harness/`)

### 자동화한 반복 작업
부하 테스트 1세트는 원래 전부 수작업이었다:

```
설정 변경 → k6 실행 → Grafana 응시 → 스크린샷 → 3회차 반복
→ 눈으로 knee point 추정 → 회차 평균 수기 계산 → 마크다운 수기 작성
```

`portfolio/*/{1회차,2회차,3회차}.png` 스크린샷들이 그 반복 노동의 흔적이다.

### 자동화 후
```
python run.py
→ k6 N회 자동 실행 (회차별 summary)
→ 회차별 시간창의 Prometheus 메트릭 자동 수집 (p95 / 에러율 / 락 경합 / 풀 / RPS)
→ Claude API가 knee point·bottleneck 보조 진단
→ matplotlib 차트 + 회차 평균±표준편차 표 + 마크다운 리포트 자동 생성
```

### AI의 역할 (보조)
- `analyzer.py`가 수집된 메트릭을 Claude API에 보내 knee point/bottleneck **보조 진단**을 생성한다.
- 리포트에는 **원본 메트릭 표/차트를 항상 동봉**해 사람이 검증할 수 있게 했다.
- AI 분석이 결론을 "결정"하지 않는다. 수집·해석·문서화라는 **반복 작업**을 거든다.

### 검증 상태 (정직)
- ✅ pytest 11개 통과 (파싱·집계·메트릭 병합 등 순수 로직)
- ✅ reporter+analyzer 합성 데이터 스모크 통과, 출력 예시 `reports/example/`
- ⚠️ 실제 k6+Prometheus end-to-end 실행은 운영 환경(AWS) 구성 후 진행 예정 (미실측)

---

## 2. 개발 워크플로우 자동화 (Claude Code 스킬)

자주 반복하는 작업을 재사용 가능한 스킬로 패키징해, 매번 맥락을 다시 설명하지 않게 했다.

| 스킬 | 자동화하는 반복 작업 |
|------|----------------------|
| `loadtest-analyze` | 측정 결과를 knee point/bottleneck 틀 + 정직성 규칙으로 분석·문서화 |
| `loadtest-compare` | 두 설정/회차 비교(ablation) — 무엇이 개선·악화 주역인지 |
| `loadtest` | 하네스 end-to-end 실행 래퍼 (사전 점검 → 실행 → 요약) |
| `commit` | 변경 검토 → 커밋 메시지 자동 생성 → 커밋 → PR 관점 분석 |

> 스킬은 로컬 개발 생산성 도구다. 핵심 산출물(하네스·워크플로우)은 스킬 없이도 동작하는
> 독립 코드로 만들어 이식성을 확보했다.

---

## 3. CI/CD 자동화 (GitHub Actions)

| 워크플로우 | 트리거 | 하는 일 |
|-----------|--------|---------|
| `loadtest-harness-ci.yml` | 하네스 변경 PR/푸시 | pytest 자동 실행 (앱 불필요) |
| `ai-pr-review.yml` | PR 열림/갱신 | **Claude가 변경 diff를 자동 코드 리뷰** → 코멘트 게시 |
| `loadtest.yml` | 수동(dispatch) | k6 서버 SSH로 부하 테스트 실행 → 리포트 아티팩트 |
| `weekly-security-audit.yml` | **매주 월요일(cron)** + 수동 | Trivy로 의존성 취약점 스캔 → AI 요약 → Issue 보고 |
| `deploy-prod.yml` | prod 푸시 | 앱 2대 병렬 배포 (기존) |

### AI PR 리뷰 봇
- PR을 올리면 `claude-opus-4-8`(env로 교체 가능)이 diff를 읽고 정확성/보안/성능/개선 관점으로 리뷰.
- "1차 의견"으로 명시하고, 발견 사항에 심각도·확신도를 붙여 사람이 필터링하도록 했다.
- 한계: GitHub 보안 정책상 포크 PR엔 secret이 없어 동작하지 않음(본인 레포 브랜치 PR에서 동작).

### 주간 보안 점검 (스케줄 자동화)
- 매주 boot jar를 빌드해 **Trivy**로 의존성 취약점(HIGH/CRITICAL)을 스캔하고, 발견 시
  Claude가 우선순위 요약을 붙여 **GitHub Issue**로 보고한다. 취약점 0건이면 이슈를 만들지 않는다.
- AI는 보조: `ANTHROPIC_API_KEY` 없으면 요약만 생략하고 원본 표는 그대로 게시.
- 트레이드오프: GitHub 자체 Dependabot 대신 Trivy를 자체 실행 → CI 비용(빌드+스캔)이 들지만
  스캐너/DB가 레포에 명시되어 재현·이식이 쉽다.

## MCP 연동 (GitHub MCP server)
- [`.mcp.json`](../.mcp.json)에 공식 **GitHub MCP server**를 등록 → Claude Code에서 이슈/PR/
  Actions를 MCP로 직접 조회·조작.
- 활성화: GitHub 공식 **원격(remote) MCP 서버**(`api.githubcopilot.com/mcp`) 사용 → Docker 불필요.
  `GITHUB_PAT` 환경변수(레포 권한 토큰)만 설정하고 Claude Code 재시작하면 로드된다.
- 트레이드오프: 기존 `gh` CLI와 기능이 겹치고, MCP는 Claude 쪽 설정이라 런타임 산출물은 아님
  (이 문서가 그 사실을 남기는 기록). 도메인 밀착도가 더 필요하면 Redis/MySQL MCP가 후보.

---

## 4. 정량 효과 (추정 명시)

| 항목 | 이전(수동) | 이후(자동) | 비고 |
|------|-----------|-----------|------|
| 부하 테스트 1세트 사이클 | 약 20~30분 | 명령 1회(수 분) | 추정치 |
| 회차 평균/편차 | 수기 계산 | 자동 평균±표준편차 | — |
| knee point/bottleneck 해석 | 매번 직접 작성 | AI 보조 초안 + 검증 | 보조 |
| 코드 리뷰 1차 | 수동 | PR마다 자동 | 보조 |
| 하네스 회귀 검증 | 없음 | pytest 11개 자동 | — |

> 시간 수치는 실측이 아니라 작업 경험 기반 추정이다. 정확한 절감치는 운영 환경에서
> end-to-end를 돌려 측정할 예정.

---

## 5. 면접용 한 줄 정리

> "부하 테스트의 실행→수집→분석→문서화 반복을 Python 파이프라인으로 자동화하고,
> knee point/bottleneck 해석과 PR 코드 리뷰에 Claude API를 **보조**로 붙였습니다.
> AI 산출물은 원본 데이터와 테스트로 검증 가능하게 만들어, 자동화의 신뢰성을 유지했습니다."
