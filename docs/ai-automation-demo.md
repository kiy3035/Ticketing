# AI 자동화 데모 — 직접 돌려보기

이 문서는 프로젝트의 AI 자동화(하네스·스킬·CI·PR 봇)가 **실제로 어떻게 동작하는지**
직접 재현·확인할 수 있도록 정리한 것이다. 각 항목은 "무엇을 / 어떻게 실행 / 무엇을 보게 되는가" 순.

> AI는 보조 도구다. 모든 산출물은 원본 데이터·테스트로 검증 가능하며, 분석/리뷰는
> "1차 의견, 최종 판단은 사람" 원칙을 따른다.

---

## 1. 부하 테스트 자동화 하네스 (앱 없이 출력 형식 확인)

`loadtest-harness/`는 `k6 실행 → Prometheus 수집 → AI 보조 진단 → 차트·리포트 생성`을
명령 1회로 묶은 파이프라인이다. 실제 k6/Prometheus 없이도 **출력 형식**을 합성 데이터로 볼 수 있다.

### 실행
```bash
cd loadtest-harness
python -m venv .venv && .venv/bin/pip install -r requirements.txt
python samples/generate_sample.py
```

### 보게 되는 것
`reports/example/`에 다음이 생성된다(커밋되어 있어 바로 확인 가능):

```
reports/example/
├── report.md                    # 회차 평균±표준편차 표 + 차트 임베드 + AI 분석 자리
└── charts/
    ├── rps.png                  # 회차(run 1/2/3) 겹쳐 그린 RPS 곡선
    ├── latency_p95.png          # p95 지연 곡선
    ├── error_rate.png           # 에러율 곡선
    └── hikari_pending.png       # DB 커넥션 풀 대기 곡선
```

차트는 matplotlib로 자체 렌더(한글 폰트 의존 없이 영문 라벨) → Grafana 수동 스크린샷을 대체한다.
회차를 한 그래프에 겹쳐 그려 **회차 간 일관성**을 한눈에 본다.

### 실제 환경 실행 (앱·k6·Prometheus 기동 시)
```bash
python run.py --scenario knee-point --repeat 3
# → reports/<timestamp>_knee-point/report.md 생성
```
설정·임계치는 `loadtest-harness/config.yaml`에서 관리(하드코딩 금지). 상세는
[loadtest-harness/README.md](../loadtest-harness/README.md).

### 회귀 검증
```bash
cd loadtest-harness && pytest -q     # 11개 (파싱·집계·메트릭 병합·skip 경로)
```

---

## 2. 개발 워크플로우 스킬 (Claude Code)

자주 반복하는 작업을 재사용 스킬로 패키징했다. `.claude/skills/`에 정의(로컬 도구).

| 스킬 | 호출 예 | 하는 일 |
|------|---------|---------|
| `loadtest-analyze` | "이 측정값 분석해줘" | knee point/bottleneck 틀 + 정직성 규칙으로 분석·포폴 초안 |
| `loadtest-compare` | "캐시 전후 비교해줘" | 두 설정/회차 ablation 비교 |
| `loadtest` | "부하 테스트 돌려줘" | 하네스 사전 점검 → 실행 → 요약 |
| `commit` | "커밋해줘" | 변경 검토 → 메시지 자동 생성 → 커밋 → PR 분석 |

### `loadtest-analyze` 실제 출력 예시
입력: 3회차 — 평균 RPS 820.5/810.1/805.9, p95 95.2/102.7/110.3ms, 실패율 0/1.02/3.41%,
hikari_pending max 6/9/11, RPS 곡선 VU 1200 부근 둔화 (합성 예시 데이터).

출력(요약):
- **Knee point**: VU 1100~1200 구간 후보 (RPS 둔화 + p95·실패율 동반 상승). VU 계단 분리 측정 필요.
- **Bottleneck**: hikari_pending 6→11 동반 상승 → DB 커넥션 풀 포화 1차 후보. 락/좌석 경합 지표는 미수집이라 단정 불가.
- **회차 일관성**: 실패율 0→1.02→3.41% 누적 악화 → 쿨다운 후 재측정 필요.
- **다음 액션**: VU 계단 측정 + 풀 사이즈 단독 변경 후 재측정(ablation).

매번 동일한 틀(knee/bottleneck + 정직성 + 고정 형식)이 자동 적용되어, 분석 품질이 일관된다.

### `commit` 실제 사용 결과
이 프로젝트의 두 PR이 `commit` 스킬로 생성됐다:
- PR #1 `feat/loadtest-harness` — 하네스·테스트·CI·예시 리포트
- PR #2 `feat/ai-productivity` — AI PR 리뷰 봇·생산성 문서 (stacked on #1)

각 PR 본문의 "변경 요약 / 영향 범위 / 테스트 상태 / 리스크 / 리뷰 포인트"가 자동 생성된 PR 분석이다.

---

## 3. CI 자동화 (GitHub Actions)

| 워크플로우 | 트리거 | 동작 | 확인 방법 |
|-----------|--------|------|-----------|
| [`loadtest-harness-ci.yml`](../.github/workflows/loadtest-harness-ci.yml) | `loadtest-harness/**` 변경 PR/푸시 | pytest 자동 | PR Checks 탭 |
| [`ai-pr-review.yml`](../.github/workflows/ai-pr-review.yml) | PR 열림/갱신 | Claude 자동 코드 리뷰 코멘트 | PR 코멘트 |
| [`loadtest.yml`](../.github/workflows/loadtest.yml) | 수동(dispatch) | k6 서버 SSH 부하 테스트 → 아티팩트 | Actions 탭 |

---

## 4. AI PR 리뷰 봇 — 켜고 보기

PR을 올리면 `claude-opus-4-8`(env `MODEL`로 교체 가능)이 변경 diff를 읽고
정확성/보안/성능 관점으로 리뷰 코멘트를 남긴다.

### 활성화 (1회)
1. GitHub → repo → Settings → Secrets and variables → Actions
2. `New repository secret` → 이름 `ANTHROPIC_API_KEY`, 값에 키 입력

### 동작 확인
- 등록 후 PR에 새 커밋을 push하거나 새 PR을 열면, Actions의 `AI PR Review`가 돌고
  PR에 "🤖 AI 코드 리뷰" 코멘트가 달린다.
- 코멘트 형식: `## 요약 / 🔴 정확성·버그 / 🔒 보안 / ⚡ 성능 / 🧹 개선 제안`,
  각 항목에 `[심각도/확신도] 파일:위치 — 내용`.

### 한계 (정직)
- GitHub 보안 정책상 **포크에서 온 PR**엔 secret이 주입되지 않아 동작하지 않는다
  (본인 레포 브랜치 PR에서는 동작). secret 미등록 시 워크플로우는 돌지만 리뷰 스텝은 실패한다.

---

## 재현 요약

```bash
# 하네스 출력 형식 보기 (앱 불필요)
cd loadtest-harness && python samples/generate_sample.py && open reports/example/report.md

# 하네스 단위 테스트
cd loadtest-harness && pytest -q

# 실제 부하 테스트 (앱·k6·Prometheus 기동 시)
cd loadtest-harness && python run.py --scenario knee-point --repeat 3

# AI PR 리뷰 봇: ANTHROPIC_API_KEY secret 등록 후 PR 생성/갱신
```

관련 문서: [AI 생산성 정리](ai-productivity.md) · [하네스 README](../loadtest-harness/README.md)
