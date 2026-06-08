# AI 도구 활용 성과 요약 (이력서·면접용)

> 콘서트 예매 시스템 프로젝트에서 **AI 도구를 활용해 반복 작업을 자동화하고, AI를 운영·검증·도메인에 엮은** 내역.
> 두 작업 세션("AI productivity" + 후속 세션)에 걸쳐 구축. **포인트는 "AI가 다 했다"가 아니라
> "AI를 보조로 쓰되 최종 판단은 사람이 하고, 비용·품질을 의사결정했다"는 것.**

---

## 0. 한 줄 요약
> 부하 테스트·코드리뷰·보안점검 등 **반복 작업을 AI 파이프라인·CI로 자동화**하고,
> AI 출력을 **테스트·가드레일·교차검증**으로 신뢰성까지 관리했다. (측정 → 가드 → 게이트로 완결)

## 1. 관통하는 원칙 (= 다른 지원자와의 차별점)
1. **AI는 1차 의견, 최종 판단은 사람** — AI 산출물엔 항상 원본 데이터/테스트를 동봉해 검증 가능하게 함.
   실제로 AI(Gemini)가 **서버 지연 단위(초)를 ms로 1000배 오독**, **cold run을 오설정으로 오해**한 걸 사람이 잡아 보강.
2. **비용·도구 의사결정** — GitHub MCP를 토큰 비용 따져 제거(→`gh` CLI), 분석 AI를 유료 Claude→**무료 Gemini**로 전환,
   CLAUDE.md를 lazy loading으로 슬림화(세션 토큰 ~52%↓). "쓸 수 있다"가 아니라 "ROI로 선택".
3. **AI 출력을 검증·가드레일** — "AI를 쓰기"만 하는 게 아니라, 결정적 검사 + AI 보조의 **하이브리드**로
   도메인 불변식을 강제하고, 비교 로직은 순수 함수로 단위 테스트.
4. **교차검증** — AI(Claude)가 짠 코드를 다른 AI(Gemini)로 비평받고, 4건 중 2건 수용·1건 부분수용·1건 기각(사람 판단).
5. **측정 → 가드 → 게이트** — 부하 테스트를 만들고 끝이 아니라, 성능 회귀를 CI에서 자동 차단하는 SLO 게이트로 닫음.

---

## 2. 산출물 (카테고리별, 실제 코드/위치 포함)

### A. 부하 테스트 자동화 하네스 (`loadtest-harness/`)
- **무엇**: k6 N회 실행 → Prometheus 메트릭 자동 수집 → AI 보조 진단 → matplotlib 차트 + 회차 평균±표준편차 표 + 마크다운 리포트 **자동 생성**. 사람이 하던 "설정→3회 실행→Grafana 스크린샷→수기 평균→md 작성"을 **명령 1회**로 대체.
- **모듈**: `k6_runner` · `prometheus_collector` · `analyzer`(Gemini) · `reporter` · `run.py` (+ `pytest` 18케이스).
- **콜드/핫 방법론 자동화**: 회차마다 Redis flush(`reset_command`)로 Redis cold, run1은 JVM cold(앱 재시작) → 수기 방법론을 그대로 재현.
- **성능 회귀 게이트** (`regression_gate.py` + `baselines/knee-point.json`): 실측 기준선 대비 p95/RPS/에러율 퇴행 시 **CI 차단(exit 1)**, 회귀 시 Gemini가 원인 가설(보조). 비교 로직은 **순수 함수로 단위 테스트**.
- **실측 검증(AWS)**: repeat 3 측정 결과(hot 2·3 평균 **RPS 2,503 · p95 462ms · 실패 3.42%**)가 **예전 수기 측정과 ±10~20% 내 일치** → "자동화가 수기 결과를 재현한다"를 정량 확인. 병목은 **DB 커넥션 풀**(hikari_pending 196~242, active≈풀 60 포화)로 자동 진단, 락 경합 0.

### B. AI 코드 품질 자동화
- **AI PR 리뷰 봇** (`.github/scripts/ai_pr_review.py`, `ai-pr-review.yml`): PR diff를 AI가 정확성/보안/성능 관점 1차 리뷰 → 코멘트.
- **도메인 가드레일** ⭐ (`.github/scripts/domain_guard.py`, `domain-guard.yml`): 이 프로젝트 **고유 불변식**(비밀값 커밋·임계치 하드코딩=결정적 / **락→트랜잭션 순서·@Transactional 범위**=AI 보조)을 PR에서 강제. **동시성·분산락이라는 프로젝트 정체성과 직결.** 결정적 CRITICAL(비밀값)만 CI 차단, AI는 확신도 표시. 결정적 로직 pytest.

### C. 보안 자동화
- **주간 보안 점검** (`.github/scripts/security_audit_summary.py`, `weekly-security-audit.yml`): 매주(cron) Trivy로 의존성 취약점 스캔 → AI가 우선순위 요약 → GitHub Issue. 0건이면 이슈 생성 안 함.

### D. AI 교차검증 (Claude ↔ Gemini)
- **`review-with-gemini` 스킬** (`.claude/skills/review-with-gemini/`): 현재 대화 또는 특정 PR을 외부 AI(Gemini, 무료)로 비평. 전송 전 비밀값 마스킹, `--dry-run`/`--save`. 사례: `docs/ai-reviews/PR1-loadtest-harness.md`(지적→판단→조치 + 원본 보관).

### E. 개발 워크플로우 자동화
- **Claude Code 스킬 5종**: `commit`(커밋+PR 자동) / `loadtest`·`loadtest-analyze`·`loadtest-compare` / `review-with-gemini`.
- **코드 품질 게이트**: `ruff` 린트/포맷 도입 + CI(`loadtest-harness-ci.yml`)에서 ruff→pytest 자동.
- **메일 알림** (`notify-pr-merged.yml`): PR 머지 시 Gmail 알림(발신=전용 계정으로 분리).
- **워크플로우 문서화**: `docs/dev-workflow.md` (Plan 먼저 → 변경·테스트·린트·커밋 작은 루프).

### F. 의사결정·정직성 기록 (문서)
- `docs/ai-productivity.md`(AI 자동화 허브) · `docs/architecture.md`·`docs/domain.md`(lazy loading 분리) · `docs/loadtest-aws-runbook.md`(AWS 실행 런북) · `docs/loadtest-manual-vs-auto.md`(수기 vs 자동 재현 비교) · `docs/ai-reviews/`(교차검증 사례).
- **MCP 제거 의사결정**, **GPT→Gemini 전환**, **lazy loading** 등을 트레이드오프와 함께 기록.

---

## 3. 정직성 / 한계 (그대로 말할 것)
- **실측 vs 추정 구분**: 부하 테스트 핵심 수치는 AWS 실측(repeat 3). 단, `error_rate`(서버 5xx)는 0 — 실패가 5xx가 아니라 timeout/429(rate limit)라 서버 메트릭에 안 잡힘(향후 서버 에러 메트릭 보강 여지).
- **AI 분석은 보조**: Gemini 분석에서 단위 오독·cold run 오해가 있었고 사람이 잡음(프롬프트 보강). 그래서 리포트엔 항상 원본 표/차트 동봉.
- **성능 게이트는 on-demand**: 부하 테스트가 AWS+~20분 필요 → 매 PR 자동이 아니라 트리거 기반(실무도 동일). 기능 회귀는 유닛테스트(매 PR), 성능 회귀는 게이트(부하 실행 시).
- **knee point VU 구간**(수기: 1,000~1,200)은 회차 평균이 아니라 **회차 내 시계열 차트**로 봐야 함.

---

## 4. 면접용 핵심 메시지 (한 줄들)
- "반복되던 부하 테스트의 실행→수집→분석→문서화를 **Python 파이프라인 + 무료 LLM**으로 자동화하고, 그 결과가 **수기 측정과 일치함을 정량 검증**했습니다."
- "범용 AI 리뷰는 흔해서, **이 프로젝트의 동시성·락 불변식**(락→트랜잭션 순서 등)을 강제하는 **도메인 가드레일**을 만들었습니다. 결정적 검사 + AI 보조 하이브리드로요."
- "AI를 **쓰기만** 한 게 아니라, AI가 짠 코드를 **다른 AI로 교차검증**하고, **성능 회귀를 CI 게이트로 자동 차단**하고, AI 출력의 **단위 오독을 사람이 잡아** 보강했습니다."
- "MCP를 토큰 비용 때문에 제거하고, 분석 LLM을 유료에서 무료로 바꾸는 등 **비용·ROI로 도구를 의사결정**했습니다."

---

## 5. 빠른 인덱스 (어디를 보면 되나)
| 영역 | 위치 |
|------|------|
| AI 자동화 진입점 | `docs/ai-productivity.md` |
| 하네스 | `loadtest-harness/` (+ `README.md`) |
| 성능 회귀 게이트 | `loadtest-harness/regression_gate.py`, `baselines/` |
| 도메인 가드레일 | `.github/scripts/domain_guard.py`, `.github/workflows/domain-guard.yml` |
| AI PR 리뷰 / 보안점검 | `.github/scripts/{ai_pr_review,security_audit_summary}.py` |
| 교차검증 스킬·사례 | `.claude/skills/review-with-gemini/`, `docs/ai-reviews/` |
| 수기 vs 자동 비교 | `docs/loadtest-manual-vs-auto.md` |
| 부하 측정 상세(수기) | `docs/load-test-portfolio.md` |
| 워크플로우 규칙 | `docs/dev-workflow.md` |
