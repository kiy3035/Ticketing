---
name: loadtest-analyze
description: 부하 테스트 결과(하네스 report.md, raw k6 summary JSON, k6 콘솔 출력, Grafana 수치)를 knee point/bottleneck 관점으로 분석하고 포트폴리오용 한국어 초안을 작성한다. 사용자가 "부하 테스트 결과 분석", "knee point 찾아줘", "이 측정값 해석/포폴 정리", "병목 진단" 등을 요청할 때 사용.
---

# 부하 테스트 결과 분석 (loadtest-analyze)

티켓팅 프로젝트의 부하 테스트 결과를 일관된 틀로 해석하고, 포트폴리오에 바로
쓸 수 있는 한국어 분석 초안을 작성한다. 이 프로젝트의 핵심 과제(대용량 트래픽,
좌석 동시 선점)와 정직성 원칙을 매번 자동으로 적용하는 것이 목적이다.

## 입력 (아래 중 무엇이든)

- `loadtest-harness/reports/<...>/report.md` (하네스 생성 리포트)
- raw k6 summary JSON (`loadtest-harness/reports/_raw/*.json`)
- k6 콘솔 출력 텍스트 (붙여넣기)
- Grafana에서 읽은 수치 / 스크린샷 설명 / 수기 메모

입력이 모호하거나 회차/VU 단계 정보가 빠졌으면 먼저 사용자에게 물어 확인한다.
추측으로 숫자를 채우지 않는다.

## 절차

1. **데이터 수집·정리**: 입력에서 VU 단계별(또는 회차별) RPS, p95/max 지연,
   실패율, 그리고 가능하면 서버측 지표(hikari_active/pending, hold_conflict,
   lock_acquire_failures, queue_waiting)를 표로 정리한다.
2. **Knee point 판단**: RPS 증가가 둔화·정체되고 p95/에러율이 급상승하는 부하
   지점을 찾는다. 명확한 knee가 안 보이면 "이 구간에선 미관측"이라고 솔직히 쓴다.
   단일 지점으로 단정하기 어려우면 후보 구간으로 제시한다.
3. **Bottleneck 진단**: knee 지점에서 동반 상승하는 자원 지표를 근거로 병목을
   지목한다. 예: hikari_pending↑ → DB 커넥션 풀 포화, lock_acquire_failures↑
   → Redis 락 경합, hold_conflict↑ → 좌석 경합. **반드시 근거 지표를 함께 인용**한다.
4. **회차 간 일관성**: 회차별 편차(표준편차)를 보고 워밍업/캐시/JVM 상태 등
   변동 요인을 짚는다.
5. **다음 액션**: 병목 가설을 검증할 후속 측정(설정 변경 → 재측정)을 제안한다.
6. **포폴 초안(요청 시)**: 노션 포트폴리오 스타일의 한국어 트러블슈팅/분석 문단을 작성한다.

## 정직성 원칙 (반드시 준수)

- 제시된 수치에서 **읽히는 사실만** 말한다. 측정하지 않은 것을 단정하지 않는다.
- AI 해석은 **보조 진단**임을 명시하고, 결론의 근거가 된 **원본 메트릭을 항상 인용**한다.
- 개선 효과를 주장할 땐 비교 기준(전/후, 대조군)을 명확히 한다. 단일 변경의 효과를
  과대 귀속하지 않는다(ablation 관점 유지).
- 불확실하면 불확실하다고 쓴다. 좋아 보이게 포장하지 않는다.

## 출력 형식

```
## 측정 데이터 정리
(표)

## Knee Point 판단
(근거 수치 포함)

## 병목(Bottleneck) 진단
(근거 지표 인용)

## 회차 간 일관성

## 다음 액션 제안

## 포폴 초안   ← 사용자가 요청한 경우에만
```

## 참고

- 메트릭 이름·PromQL: `docs/monitoring.md`, `loadtest-harness/config.yaml`
- 프로젝트 규칙: knee point / bottleneck 관점으로 해석 (CLAUDE.md)
