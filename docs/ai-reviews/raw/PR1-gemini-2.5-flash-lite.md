# 🔍 외부 AI(Gemini, gemini-2.5-flash-lite) 비평

## 코드 리뷰

### 1. 버그, 엣지케이스, 동시성/트랜잭션 문제

*   **`loadtest-harness/k6_runner.py` - `run_scenario` 함수**:
    *   k6 실행 시 `subprocess.run`의 `capture_output=True`와 `text=True` 옵션은 표준 출력 및 에러를 메모리에 저장합니다. 대규모 테스트나 긴 실행 시간 동안 메모리 사용량이 증가할 수 있습니다. 실행 결과가 매우 클 경우 문제가 될 수 있습니다. (추정)
    *   k6 실행 실패 시 `proc.returncode != 0` 조건에서 stderr의 일부만 로그로 남깁니다. 실패 원인 파악에 충분하지 않을 수 있습니다. 전체 stderr를 저장하거나, 실패 시 더 상세한 로깅이 필요할 수 있습니다.
    *   `summary_path.parent.mkdir(parents=True, exist_ok=True)`는 이미 디렉토리가 존재하면 아무것도 하지 않지만, 병렬 실행 시 경쟁 조건이 발생할 가능성은 낮습니다.
    *   `json.loads(summary_path.read_text(encoding="utf-8"))`에서 `JSONDecodeError` 발생 시 로그만 남기고 빈 딕셔너리를 반환합니다. 이로 인해 후속 분석에서 `KeyError` 등이 발생할 수 있습니다. `_extract_metrics` 함수가 빈 딕셔너리를 받아도 안전하게 처리하도록 설계되어 있어 큰 문제는 없어 보이나, 명시적인 오류 처리가 더 좋을 수 있습니다.

*   **`loadtest-harness/prometheus_collector.py` - `_query_range` 함수**:
    *   Prometheus API 응답에서 `payload.get("status") != "success"` 체크 후 빈 리스트를 반환합니다. Prometheus API 오류 메시지 자체를 로깅하거나 반환하여 디버깅을 용이하게 하는 것이 좋습니다.
    *   `requests.get`의 `timeout=30`은 고정값입니다. 네트워크 환경이나 Prometheus 응답 시간에 따라 타임아웃이 발생할 수 있습니다. 동적으로 조절하거나 설정 파일에서 관리하는 것이 유연성을 높일 수 있습니다.
    *   `merged[float(ts)] = merged.get(float(ts), 0.0) + float(val)` 부분에서 `float(ts)` 변환 시 `ValueError`가 발생할 수 있습니다. `try-except` 블록으로 감싸거나, `ts`가 항상 유효한 숫자임을 보장해야 합니다. 현재는 `continue`로 넘어가지만, 이로 인해 데이터 누락이 발생할 수 있습니다.

*   **`loadtest-harness/reporter.py` - `_plot_metric` 함수**:
    *   `matplotlib.use("Agg")` 설정은 GUI 없는 환경에 적합하지만, 폰트 설정이 누락되어 한글이 깨질 수 있습니다. 현재는 영문 레이블만 사용하므로 문제가 없으나, 향후 한글 레이블 추가 시 폰트 설정이 필요합니다.
    *   `ax.plot`에서 `label`에 `r.run_index`를 사용하는데, `r` 객체가 `RunResult` 타입인지 명확히 해야 합니다. (코드상으로는 명확해 보입니다.)
    *   `fig.tight_layout()`은 레이아웃을 자동으로 조정하지만, 복잡한 그래프에서는 겹침이 발생할 수 있습니다.

*   **`loadtest-harness/run.py` - `main` 함수**:
    *   `time.sleep(settle)`은 Prometheus 데이터 반영을 기다리는 용도인데, `settle_seconds` 값이 실제 Prometheus 스크랩 주기 및 처리 시간에 비해 너무 짧거나 길 수 있습니다. 테스트 환경에 따라 조절이 필요할 수 있습니다.
    *   시나리오 실행 중 에러 발생 시 전체 프로세스가 종료될 수 있습니다. 각 시나리오 실행을 `try-except` 블록으로 감싸서 특정 시나리오 실패가 전체 실행을 중단시키지 않도록 하는 것이 좋습니다. (현재는 `log.error`만 하고 넘어가는 것으로 보입니다.)

### 2. 보안 취약점

*   **`.github/workflows/loadtest.yml`**:
    *   `K6_SSH_KEY`와 `ANTHROPIC_API_KEY`를 GitHub Secrets으로 관리하는 것은 올바른 접근 방식입니다.
    *   `echo "$K6_KEY" > k6_key.pem` 및 `chmod 600 k6_key.pem`으로 SSH 키를 파일로 저장하고 권한을 설정하는 것은 일반적인 방법입니다. 다만, 이 키 파일이 워크플로우 실행 환경에 잠시 노출될 수 있음을 인지해야 합니다. (일반적인 CI/CD 환경에서는 허용되는 수준입니다.)
    *   `ssh $SSHOPT "$REMOTE" "..."` 명령 내에서 `ANTHROPIC_API_KEY='${ANTHROPIC_API_KEY}'`와 같이 환경 변수를 직접 전달하는 방식은, 해당 변수가 쉘에서 노출될 위험이 있습니다. `ssh` 명령 자체의 `env` 옵션 등을 활용하여 더 안전하게 전달하는 방안을 고려할 수 있습니다. (현재는 `run` 스크립트 내에서 직접 사용되므로 큰 문제는 없어 보입니다.)

*   **`loadtest-harness/config.yaml`**:
    *   `ANTHROPIC_API_KEY`를 환경 변수로 읽도록 되어 있어 설정 파일 자체에 노출되지 않는 것은 좋습니다.

### 3. 성능·자원 누수 우려

*   **`loadtest-harness/k6_runner.py`**:
    *   `subprocess.run`에서 `capture_output=True` 사용 시, 대규모 출력이 발생하는 경우 메모리 누수가 발생할 수 있습니다. (앞서 언급)

*   **`loadtest-harness/prometheus_collector.py`**:
    *   `requests.get` 호출 시 `timeout` 값이 없으면, 요청이 무한정 대기할 수 있습니다. 현재 30초로 설정되어 있지만, 이 값도 환경에 따라 조절이 필요할 수 있습니다.
    *   `_query_range` 함수에서 `merged` 딕셔너리에 데이터를 계속 추가합니다. 만약 Prometheus 응답 데이터 포인트가 매우 많다면 메모리 사용량이 증가할 수 있습니다. (하지만 Prometheus의 `step` 설정으로 인해 과도한 데이터 포인트는 발생하지 않을 것으로 추정됩니다.)

*   **`loadtest-harness/reporter.py`**:
    *   `matplotlib`을 사용하여 그래프를 생성하는데, 수백 개의 그래프를 동시에 생성하거나 매우 복잡한 그래프를 생성할 경우 CPU 및 메모리 사용량이 증가할 수 있습니다. 현재는 각 시나리오별로 그래프를 생성하므로 큰 문제는 없을 것으로 보입니다.
    *   `matplotlib.use("Agg")`는 GUI 백엔드를 사용하지 않아 메모리 사용량을 줄이는 데 도움이 됩니다.

### 4. 설계/가독성 개선점과 더 나은 대안

*   **전반적인 설계**:
    *   `loadtest-harness/` 디렉토리에 모든 관련 코드를 모아둔 것은 좋은 격리입니다.
    *   `config.yaml`을 통해 설정을 외부화한 점은 매우 훌륭합니다.
    *   각 모듈(`k6_runner`, `prometheus_collector`, `analyzer`, `reporter`)의 역할 분담이 명확해 보입니다.
    *   `RunResult` 데이터 클래스를 사용하여 k6 실행 결과를 구조화한 점은 가독성을 높입니다.
    *   `conftest.py`를 통해 pytest가 하네스 모듈을 import할 수 있도록 path를 설정한 것은 표준적인 방법입니다.

*   **`loadtest-harness/README.md`**:
    *   "왜 만들었나" 섹션에서 기존 수동 작업과 자동화 후의 비교가 명확하여 이해하기 쉽습니다.
    *   "구성" 섹션에서 각 파일의 역할을 잘 설명하고 있습니다.
    *   "사용법" 섹션에서 명령어 예시가 명확합니다.
    *   "설계 원칙"에서 AI 분석의 역할과 설정 외부화 원칙을 명시한 점은 좋습니다.

*   **`loadtest-harness/config.yaml`**:
    *   `prometheus.queries`에 `__APP__` 토큰을 사용하고 `prometheus_collector.py`에서 치환하는 방식은 유연하고 좋습니다.
    *   `analyzer.model` 설정 시 `claude-opus-4-8`과 `claude-sonnet-4-6`을 비교하며 비용 절감을 고려할 수 있다는 점은 실용적입니다.

*   **`loadtest-harness/k6_runner.py`**:
    *   `_extract_metrics` 함수에서 k6 요약 JSON을 파싱하여 필요한 지표만 추출하는 로직은 잘 설계되었습니다. `metrics.get(name, {}).get(key, default)` 패턴은 안전하고 가독성이 좋습니다.
    *   `run_scenario` 함수에서 `subprocess.run`으로 k6를 실행하고 결과를 `RunResult` 객체로 반환하는 흐름은 명확합니다.

*   **`loadtest-harness/prometheus_collector.py`**:
    *   `PrometheusCollector` 클래스로 Prometheus API 연동 로직을 캡슐화한 것은 좋은 객체 지향 설계입니다.
    *   `_query_range` 함수에서 여러 시리즈를 timestamp 기준으로 합산하는 로직은 인스턴스가 여러 개일 때 유용합니다. (다만, 이 로직이 항상 필요한지, 아니면 특정 메트릭에만 적용되어야 하는지에 대한 고려가 필요할 수 있습니다.)
    *   `collect` 함수에서 반환하는 딕셔너리 구조 (`{"metric_name": {"series": ..., "max": ..., "mean": ...}}`)는 후속 처리에 용이해 보입니다.

*   **`loadtest-harness/analyzer.py`**:
    *   `_SYSTEM` 프롬프트에 AI의 역할과 출력 형식을 명확하게 정의한 점은 좋은 프롬프트 엔지니어링입니다.
    *   `_build_user_prompt` 함수에서 k6 메트릭과 Prometheus 메트릭을 보기 좋게 직렬화하는 로직은 잘 구현되었습니다.
    *   `ANTHROPIC_API_KEY` 환경 변수 존재 여부 체크 및 `anthropic` 패키지 임포트 체크를 통해 API 키가 없거나 라이브러리가 설치되지 않았을 때 gracefully fallback하는 점은 사용자 경험 측면에서 좋습니다.

*   **`loadtest-harness/reporter.py`**:
    *   `_plot_metric` 함수에서 회차별 시계열을 한 차트에 겹쳐 그리는 방식은 성능 추세를 비교하는 데 매우 효과적입니다.
    *   `_aggregate_table` 함수에서 k6 핵심 지표의 평균 ± 표준편차를 계산하는 로직은 수동 작업을 대체하는 핵심 기능이며 잘 구현되었습니다.
    *   `write_report` 함수에서 마크다운 리포트 생성 로직은 구조화되어 있고, 차트 이미지 경로를 상대 경로로 처리하는 등 세심한 부분이 보입니다.

*   **`loadtest-harness/run.py`**:
    *   `argparse`를 사용하여 커맨드라인 인자를 처리하는 것은 표준적이고 좋습니다.
    *   `--scenario`, `--repeat`, `--no-analyze` 옵션은 유연한 실행을 지원합니다.
    *   각 시나리오별로 독립적으로 실행하고 결과를 취합하는 흐름은 명확합니다.

*   **`loadtest-harness/samples/generate_sample.py`**:
    *   합성 예시 데이터를 생성하여 리포트 형식을 보여주는 것은 매우 좋은 아이디어입니다. 실제 데이터 없이도 리포트의 구조와 내용을 미리 확인할 수 있습니다.
    *   합성 예시임을 명확히 하기 위해 파일 상단에 배너를 추가한 것은 정직성을 보여줍니다.

*   **`loadtest-harness/tests/`**:
    *   각 모듈의 핵심 로직(파싱, 집계, 프롬프트 생성 등)에 대한 단위 테스트가 잘 작성되어 있습니다.
    *   `monkeypatch`를 사용하여 외부 의존성(네트워크, 환경 변수) 없이 테스트하는 방식은 훌륭합니다.
    *   `test_prometheus_collector.py`의 `test_app_라벨_치환됨` 테스트는 `__APP__` 토큰 치환 로직을 정확히 검증합니다.
    *   `test_reporter.py`의 `test_평균과_표준편차_계산` 테스트는 통계 계산 로직을 검증합니다.

*   **개선 제안**:
    *   **`loadtest-harness/prometheus_collector.py` - 멀티시리즈 합산**: 현재 `_query_range` 함수에서 모든 메트릭에 대해 timestamp 기준으로 합산합니다. 예를 들어 `hikari_active`와 같이 인스턴스별로 개별적으로 봐야 의미 있는 메트릭이 있다면, 이 합산 로직이 적절하지 않을 수 있습니다. 메트릭별로 합산 여부를 결정하거나, 합산 시 인스턴스 정보 등을 포함하여 반환하는 방식을 고려할 수 있습니다. (예: `promql`에 `by (instance)`를 추가하거나, `queries` 설정에 `aggregate: true/false` 옵션 추가)
    *   **`loadtest-harness/analyzer.py` - 프롬프트**: `_SYSTEM` 프롬프트에서 "반드시 knee point(...)와 bottleneck(...) 관점으로 진단하라."라고 강제하고 있습니다. AI가 때로는 다른 관점에서 더 유용한 인사이트를 줄 수도 있습니다. "주로 ~ 관점으로 진단하되, 다른 중요한 관점이 있다면 함께 제시하라" 와 같이 좀 더 유연한 지시가 좋을 수 있습니다. (추정)
    *   **`loadtest-harness/run.py` - 에러 핸들링**: 각 시나리오 실행 후 `prom_per_run.append(collector.collect(...))` 부분에서 `collector.collect`가 실패할 경우, `prom_per_run` 리스트에 빈 딕셔너리가 추가될 것입니다. 이 경우 `analyzer.analyze`나 `reporter.write_report`에서 `IndexError` 또는 `KeyError`가 발생할 수 있습니다. `try-except`로 `collector.collect`를 감싸고, 실패 시 명확한 로그와 함께 빈 딕셔너리 대신 `None` 등을 반환하도록 처리하는 것이 안전합니다.

### 5. PR 본문 설명과 실제 diff의 불일치, 과장/미검증 주장

*   **PR 본문**:
    *   "기존의 수동 반복(설정별 3회 실행·Grafana 스크린샷·수기 분석)을 명령 한 번으로 대체." → 실제 `run.py`의 동작 방식과 일치합니다.
    *   "테스트 상태" 섹션에서 "실제 k6+Prometheus end-to-end, AI 분석 실측은 미실행 (앱 미기동 — 추후 검증)"이라고 명시한 점은 매우 정직하고 좋습니다.
    *   "리스크 · 주의" 섹션에서 secrets 미등록 상태를 명시한 점도 좋습니다.
    *   "리뷰 포인트"에서 구체적인 검토 요청 사항을 제시한 점은 리뷰어에게 도움이 됩니다.
    *   `loadtest.yml` 설명에서 "deploy-prod.yml의 SSH/secrets 패턴을 재사용한다"는 언급은 기존 코드와의 연관성을 보여줍니다.

*   **Diff**:
    *   전체적으로 PR 본문의 설명과 diff 내용이 잘 일치합니다. 새로운 디렉토리 구조, 워크플로우 파일, Python 모듈들이 추가되었으며, 각 파일의 목적은 README 및 코드 내 주석을 통해 잘 설명되어 있습니다.
    *   "과장/미검증 주장"은 발견되지 않았습니다. 오히려 "미실행", "추후 검증" 등의 표현을 통해 신중함을 보이고 있습니다.

### 총평

이 PR은 부하 테스트 자동화라는 복잡한 기능을 체계적으로 구현했습니다. 코드의 품질, 설계의 견고함, 문서화 수준 모두 매우 높습니다. 특히 다음과 같은 점들이 인상 깊습니다.

*   **명확한 역할 분담**: 각 Python 모듈과 GitHub Actions 워크플로우의 책임이 명확합니다.
*   **설정 외부화**: `config.yaml`을 통해 모든 주요 설정을 관리하여 유연성과 확장성을 확보했습니다.
*   **정직한 문서화**: 테스트 상태, 리스크, AI 분석의 한계를 명확히 명시하여 오해의 소지를 줄였습니다.
*   **테스트 커버리지**: 핵심 로직에 대한 단위 테스트가 잘 작성되어 있습니다.
*   **샘플 데이터 생성**: `generate_sample.py`를 통해 리포트 형식을 미리 확인할 수 있도록 한 점은 사용자 편의성을 크게 높입니다.

몇 가지 사소한 개선점(에러 핸들링, Prometheus 멀티시리즈 처리 방식 등)이 있지만, 이는 기능 자체의 완성도를 해치지 않는 수준입니다. 전반적으로 매우 잘 작성된 코드이며, 훌륭한 PR입니다.