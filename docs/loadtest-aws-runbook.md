# AWS 부하 테스트 실행 런북 (하네스 end-to-end)

**목적**: 자동화 하네스(`loadtest-harness/`)를 **실제 AWS 환경**에서 한 번에 실행해,
"수기(Before) → 자동(After)"를 **같은 환경에서** 닫는다. (로컬은 대표성이 없어 실측은 AWS에서)

> 오케스트레이션은 [`loadtest.yml`](../.github/workflows/loadtest.yml)(수동 dispatch)이 담당한다.

## 0. 구성 개요 (데이터 흐름)
```
GitHub Actions(runner)
   └─ SSH ─▶ k6 서버(EC2)            : 하네스 실행 주체
                 └─ HTTP 부하 ─▶ nginx ─▶ 앱서버 ×2 ─▶ RDS(MySQL)/Redis/Kafka
                 └─ 메트릭 수집 ◀─ Prometheus
   ◀─ scp ─ 리포트 회수 → Actions 아티팩트
```
- k6 **부하는 k6 서버에서** 생성된다. GitHub 러너는 SSH로 시키는 오케스트레이터일 뿐.
- 대상 URL·Prometheus·공연 ID 등은 **`loadtest-harness/config.yaml`** 이 관리.

---

## 1. 최초 1회 셋업

### 1-1. AWS 인프라 기동
- **인프라 서버**(t3a.medium): Redis · Kafka · Prometheus · Grafana · **nginx**(LB)
- **앱서버 ×2**(t3a.small): 앱 기동(`SPRING_PROFILES_ACTIVE=prod`, `.env`로 RDS/Redis/Kafka 주소)
- **RDS**(MySQL): `ticketing` DB, Flyway 마이그레이션 적용됨
- **k6 서버**(t3a.small): `k6` · `python3` · `git` 설치 + **Prometheus(9090) 접근 가능**

### 1-2. 시드 데이터
- 테스트할 **공연/좌석 생성**(예: `concert_id=49`). 예전 수기 테스트와 동일 ID로 두면 비교가 쉽다.
- 확인: `POST /api/queue/enter?concertId=49` 가 201을 주면 OK.

### 1-3. 하네스 config를 AWS로 지정 ⚠️ (기본값이 localhost라 반드시 수정)
`loadtest-harness/config.yaml` 수정 후 **커밋**(워크플로우가 레포를 checkout 함):
```yaml
k6:
  base_url: http://<nginx_내부IP>:80   # 예: http://172.31.37.156:80
  concert_id: 49                       # 시드한 공연 ID
prometheus:
  base_url: http://<prometheus_IP>:9090  # k6 서버에서 도달 가능한 주소
  app_label: ticketing                   # 앱 management.metrics.tags.application 과 일치
```
> 내부 IP는 비밀이 아니라 config에 둬도 무방. 자주 바꾸면 별도 `config-aws.yaml` + `run.py --config` 도 가능.

### 1-4. GitHub Secrets 3개 등록
**Settings → Secrets and variables → Actions → Secrets → New repository secret**

| Secret | 값 | 비고 |
|--------|----|------|
| `K6_HOST` | k6 서버 public IP/도메인 | `ubuntu` 계정 가정 |
| `K6_SSH_KEY` | k6 서버 SSH 개인키 **PEM 전문** | 절대 평문 커밋 금지 |
| `ANTHROPIC_API_KEY` | Claude API 키(`sk-ant-...`) | **선택** — 없으면 AI 분석만 생략. [console.anthropic.com](https://console.anthropic.com) → API Keys → Create Key (유료) |

> 전제: k6 서버 보안그룹에 **22(SSH)** 오픈, 앱/Prometheus로의 내부 통신 허용.

---

## 2. 실행
**Actions UI**: "Load test (manual)" → **Run workflow** → `scenario=knee-point`, `repeat=3`

또는 **CLI**:
```bash
gh workflow run loadtest.yml -f scenario=knee-point -f repeat=3
gh run list --workflow=loadtest.yml --limit 1      # run id 확인
gh run watch <run-id>                              # 진행 추적
```
> 다른 시나리오 예: `concurrent-hold` / `queue-flow` / `full-flow` (config.yaml의 `k6.scenarios`에 정의돼 있어야 함).

---

## 3. 결과 회수
- 완료된 run의 **Artifacts → `loadtest-report-knee-point`** 다운로드
- 또는 CLI: `gh run download <run-id> -n loadtest-report-knee-point`
- 내용: 마크다운 리포트 + 차트 PNG + 회차별 raw summary JSON(검증용 원본)

---

## 4. 검증 · 활용
1. **자동 리포트 수치 vs 예전 수기 결과 비교** → 자동화 신뢰성 입증("AI/자동 결론이 손으로 낸 것과 일치하는가").
2. `loadtest-analyze` 스킬로 knee point/bottleneck 포폴 초안 정리.
3. 서로 다른 설정 회차는 `loadtest-compare`로 ablation.

---

## 5. 트러블슈팅
| 증상 | 원인 / 조치 |
|------|------------|
| 대기열 진입 201 실패(전부 fail) | `concert_id`가 DB에 없음 → 시드 확인(1-2) |
| Prometheus 메트릭이 0/None | `prometheus.base_url`이 k6 서버에서 도달 불가, 또는 `app_label` 불일치 |
| SSH 실패 | `K6_HOST`/`K6_SSH_KEY` 확인, 보안그룹 22 오픈, `ubuntu` 계정 맞는지 |
| 리포트에 AI 분석 없음 | `ANTHROPIC_API_KEY` 미등록 → **정상**(분석만 생략) |
| k6 rc=99로 종료 | threshold 미달 — knee point 탐지에선 **정상 상황**(경고만) |
| 멀티시리즈 합산 경고 | 인스턴스 분리 메트릭은 PromQL에서 `sum()`/`by(...)`로 미리 집계 |

---

## 6. 비용 메모
- `ANTHROPIC_API_KEY`(Claude)는 **유료**(무료 티어 없음). 분석 1회 비용은 소액.
- 비용 0으로 가려면: 키 미등록(분석 생략) 또는 **하네스 분석기를 무료 Gemini로 전환**(별도 작업 — `review-with-gemini`와 동일한 OpenAI 호환 방식 적용 가능).
