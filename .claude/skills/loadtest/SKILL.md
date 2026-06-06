---
name: loadtest
description: 부하 테스트 자동화 하네스(loadtest-harness)를 end-to-end 실행한다 — 사전 점검 → run.py 실행(시나리오·회차 선택) → 생성된 리포트 요약까지. 사용자가 "부하 테스트 돌려줘", "하네스 실행", "knee-point 테스트 실행", "loadtest 돌려" 등을 요청할 때 사용. (대상 앱·k6·Prometheus가 떠 있어야 함)
---

# 부하 테스트 하네스 실행 (loadtest)

`loadtest-harness/`를 한 번에 실행하는 래퍼. 사람이 매번 하던 "설정 확인 →
k6 N회 실행 → 메트릭 수집 → 리포트 확인"을 명령 한 번으로 묶는다.

## 사전 점검 (실행 전 반드시 확인)

1. **대상 앱 기동 여부**: `config.yaml`의 `k6.base_url`(기본 nginx/앱) 응답 확인.
   안 떠 있으면 실행하지 말고 사용자에게 알린다.
2. **Prometheus 접근**: `config.yaml`의 `prometheus.base_url` 도달 가능 확인.
3. **k6 설치**: `k6 version` 확인.
4. **Python 환경**: `loadtest-harness/.venv` 존재 확인. 없으면
   `python -m venv .venv && .venv/bin/pip install -r requirements.txt` 안내.
5. **AI 분석**: `ANTHROPIC_API_KEY` 환경변수 유무 확인(없으면 분석은 자동 생략되며,
   필요하면 사용자에게 설정 안내).

하나라도 미충족이면 **실행을 중단하고** 무엇이 빠졌는지 사용자에게 보고한다.
앱/인프라를 임의로 띄우려 하지 않는다.

## 실행

`loadtest-harness/` 디렉토리에서:

```bash
.venv/bin/python run.py --scenario <이름> --repeat <횟수>
```

- 시나리오·회차는 사용자에게 확인하거나 `config.yaml` 기본값 사용.
- 빠른 점검이면 `--repeat 1 --no-analyze` 권장.
- 운영 환경(원격 k6 서버)에서 돌릴 땐 GitHub Actions `loadtest.yml`
  (`workflow_dispatch`) 사용을 안내한다.

## 실행 후

1. 생성된 `reports/<timestamp>_<scenario>/report.md` 경로를 보고한다.
2. 리포트의 회차 요약 표(평균 RPS, p95, 실패율)와 AI 분석 핵심을 간단히 요약한다.
3. 더 깊은 해석이 필요하면 `loadtest-analyze`, 다른 설정과 비교하려면
   `loadtest-compare` 스킬로 이어가도록 안내한다.

## 정직성

- k6가 threshold 미달로 비정상 종료(rc=99)해도 knee point 탐지에선 정상 상황이다.
  실패를 숨기지 말고 실패율·에러를 그대로 보고한다.
- 측정값을 임의로 보정하거나 좋게 해석하지 않는다.

## 참고

- 설정: `loadtest-harness/config.yaml`
- 상세: `loadtest-harness/README.md`
