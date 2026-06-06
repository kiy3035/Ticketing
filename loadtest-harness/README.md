# 부하 테스트 자동화 하네스 (loadtest-harness)

k6 부하 테스트 실행 → 서버 메트릭 수집 → AI 보조 분석 → 리포트 생성까지를
**명령 한 번으로 자동화**하는 파이프라인. 기존의 수동 반복 작업을 대체한다.

## 왜 만들었나 (자동화 대상)

기존 부하 테스트 워크플로우는 전부 수동이었다:

```
설정 변경 → k6 실행 → Grafana 응시 → 스크린샷 → 3회차 반복
→ 눈으로 knee point 추정 → 평균 수기 계산 → 마크다운 수기 작성
```

`portfolio/` 폴더의 `1회차/2회차/3회차` 스크린샷이 그 반복 노동의 흔적이다.
설정 1개당 20~30분, 설정이 십수 개라 누적 수 시간이 들었다.

자동화 후:

```
python run.py
→ k6 N회 자동 실행 (회차별 summary JSON)
→ 회차별 실행 시간창의 Prometheus 메트릭 자동 수집 (p95 / 에러율 / 락경합 / 풀 / RPS)
→ Claude API가 knee point·bottleneck 보조 진단
→ matplotlib 차트 + 회차 평균±표준편차 표 + 마크다운 리포트 자동 생성
```

## 구성

| 파일 | 역할 |
|------|------|
| `config.yaml` | URL·반복횟수·PromQL·모델 등 모든 설정 외부화 (하드코딩 금지) |
| `run.py` | 오케스트레이터 (runner → collector → analyzer → reporter) |
| `k6_runner.py` | k6 N회 실행 + summary JSON 파싱 + 시간창 기록 |
| `prometheus_collector.py` | 시간창 동안 PromQL range query 수집 |
| `analyzer.py` | Claude API 호출 → knee point/bottleneck 보조 분석 |
| `reporter.py` | matplotlib 차트 + 마크다운 리포트 생성 |
| `reports/` | 생성된 리포트·차트·원본 summary JSON 보관 (커밋) |

## 사용법

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-...        # AI 분석용 (없으면 분석만 건너뛰고 진행)

python run.py                          # config의 모든 시나리오
python run.py --scenario knee-point    # 특정 시나리오만
python run.py --repeat 1 --no-analyze  # 빠른 점검 (1회, AI 분석 생략)
```

전제: k6 설치, 대상 앱 기동, Prometheus 접근 가능 (URL은 `config.yaml`).

## 테스트

파싱·집계·메트릭 병합 등 순수 로직은 앱/인프라 없이 단위 테스트로 검증한다.

```bash
pip install -r requirements-dev.txt
pytest -q              # tests/ 11개 케이스
```

`.github/workflows/loadtest-harness-ci.yml` 가 `loadtest-harness/**` 변경 시
PR/푸시에서 자동으로 pytest를 돌린다 (앱 불필요).

## 출력 예시

`reports/example/report.md` 는 하네스 출력 **형식**을 보여주는 합성 예시다
(실측 아님, `samples/generate_sample.py`로 재생성). 회차 표·차트·AI 분석 자리를 확인할 수 있다.

## 실행 자동화 (수동 트리거)

`.github/workflows/loadtest.yml` — `workflow_dispatch`로 시나리오·회차를 입력하면
k6 서버에 SSH로 하네스를 실행하고 리포트를 아티팩트로 회수한다.
필요한 Secrets: `K6_HOST`, `K6_SSH_KEY`, `ANTHROPIC_API_KEY` (워크플로우 상단 주석 참고).

## 설계 원칙

- **AI는 보조 진단**: 리포트엔 항상 원본 메트릭 표/차트를 같이 실어 사람이 검증 가능.
  "AI가 결론을 냈다"가 아니라 "수집·해석·문서화 반복 작업을 자동화했다"가 정확한 설명.
- **설정 외부화**: 메트릭/임계치가 바뀌면 코드가 아니라 `config.yaml`만 수정.
- **이식성**: Claude Code 등 특정 도구에 의존하지 않는 독립 스크립트 → 어떤 CI에서도 실행 가능.

## 로드맵

- [x] **Phase 1 — 로컬 MVP**: 위 파이프라인 + 단위 테스트 + 합성 예시 리포트
- [x] **Phase 2 — GitHub Actions**: 실행 워크플로우(`loadtest.yml`) + 하네스 CI(`loadtest-harness-ci.yml`) 작성
      *(실제 구동은 k6 서버·secrets 구성 후)*
- [ ] **Phase 3 — Claude Code 스킬**: 자주 쓰는 호출 흐름을 스킬로 래핑 (개발 생산성)
