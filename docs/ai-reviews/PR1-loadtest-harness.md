# 교차검증 사례: 부하 테스트 하네스 (PR #1) — Claude vs Gemini

> **이 문서가 보여주려는 것**: AI(Claude)가 작성한 코드를 *다른 AI(Gemini)* 로 교차검증하고,
> 그 지적을 **사람이 판단**해 수용/부분수용/기각을 결정한 뒤 실제로 반영한 전 과정.
> "AI가 다 했다"가 아니라 **AI 비평 → 사람 판단 → 반영** 루프를 증명하는 기록이다.

- 대상: PR #1 `부하 테스트 자동화 하네스 추가` (`loadtest-harness/`)
- 1차 작성: Claude Code
- 교차검증: Google **Gemini** (`gemini-2.5-flash-lite`), `review-with-gemini` 스킬로 자동 호출
- 원본 Gemini 출력(가공 없음): [`raw/PR1-gemini-2.5-flash-lite.md`](raw/PR1-gemini-2.5-flash-lite.md)
- 재현: `python .claude/skills/review-with-gemini/review_with_gemini.py --pr 1 --save`

## Gemini 지적 → 사람 판단 → 조치

| # | Gemini 지적 | 판단 | 조치 |
|---|-------------|------|------|
| 1 | `run.py`: 시나리오/회차 수집 실패 시 빈 dict가 흘러가 downstream에서 `KeyError`/`IndexError` 가능. try-except로 격리 권장 | ✅ **수용** (실제 견고성 결함) | 시나리오 단위 try/except + 회차별 수집 격리, 실패 시 건너뛰고 CI가 알도록 비정상 종료 |
| 2 | `prometheus_collector._query_range`: 멀티시리즈를 무조건 timestamp 합산 → 인스턴스별로 봐야 할 메트릭엔 부적절. per-metric 집계 옵션 제안 | 🟡 **부분 수용** | 현재 모든 쿼리가 PromQL에서 `sum()`으로 단일 시리즈를 반환해 실제 버그는 미발생. 합산은 의도된 동작이며 **테스트로 고정**돼 있음. 그래서 스키마를 바꾸는 대신 **합산 발생 시 경고 로그 + 문서화**로 "숨은 동작"을 가시화. (per-metric 옵션은 per-instance 쿼리가 생길 때 도입) |
| 3 | `k6_runner`: 실패 시 stderr 일부(300자)만 로깅 → 원인 파악 부족 | ✅ **수용** | 비정상 종료 시 **stderr 전체를 파일로 보관**(`reports/_raw/{name}_run{i}_stderr.txt`). 300자 미리보기 로그는 유지 |
| 4 | `analyzer`: knee point/bottleneck 관점을 "반드시"로 강제 → 더 유연하게 | ❌ **기각** | 이 프로젝트는 의도적으로 그 관점을 강제(포트폴리오 일관성·재현성). 취향 차이라 변경 불필요로 판단 |
| - | 메모리/타임아웃 우려(`capture_output` 대용량 등) | ❌ **보류** | Gemini 스스로 "추정"이라 표기. 현재 규모(회차당 요약 1건)에선 영향 무시 가능 |

> 전반 평가: Gemini는 PR을 "매우 잘 작성된 PR"로 보았고, **과장/미검증 주장은 "발견 안 됨"**.
> 즉 큰 결함이 아니라 견고성·관측성 개선 위주였고, 그중 타당한 3건을 반영했다.

## 실제 반영 (이 PR에서)

- `loadtest-harness/run.py` — 시나리오/회차 수집 에러 격리 + 실패 요약/비정상 종료
- `loadtest-harness/prometheus_collector.py` — 멀티시리즈 합산 경고 로그 + 의도 문서화
- `loadtest-harness/k6_runner.py` — 실패 시 stderr 전체 파일 보관
- 검증: 기존 pytest **11개 전부 통과**(회귀 없음), 3개 모듈 `py_compile` 통과

## 메타 — 이 루프에서 배운 것

- AI 비평을 **맹신하지 않았다**: 4건 중 2건 수용, 1건은 의도적 범위 축소(부분 수용), 2건 기각.
- 특히 #2는 "AI가 지적했으니 큰 리팩터" 가 아니라, **현재 코드·테스트·실사용 맥락을 확인**한 뒤
  비용 대비 가치가 맞는 **비례적 대응**(경고+문서화)을 택했다 — 과잉 대응 회피.
- 도구화의 효과: 교차검증이 `--pr N` 한 번으로 끝나, "다른 AI에게 보여주기"의 마찰이 사라졌다.
