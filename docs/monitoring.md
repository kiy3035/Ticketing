# Prometheus & Grafana 최소 가이드

부하 테스트·운영 시 지표 확인을 위한 최소 설정입니다.

## 1. Prometheus

### 1) 설치 (로컬 예시)

- [Prometheus 다운로드](https://prometheus.io/download/) 또는 Docker 사용
- Docker 예시:
  ```bash
  docker run -d --name prometheus -p 9090:9090 \
    -v ${PWD}/prometheus.yml:/etc/prometheus/prometheus.yml \
    prom/prometheus:latest
  ```

### 2) scrape 설정 (`prometheus.yml`)

앱이 `http://localhost:8080`에서 실행 중이라면:

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'ticketing'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']   # Docker 내부에서 호스트 앱 접근
        # 로컬에서 Prometheus 실행 시: ['localhost:8080']
```

- 앱에 `management.endpoints.web.exposure.include=health,metrics,prometheus` 가 이미 포함되어 있으면 `/actuator/prometheus` 에서 메트릭 노출됨.

### 3) 확인

- Prometheus UI: http://localhost:9090
- 쿼리 예: `ticketing_queue_waiting_count` , `ticketing_lock_acquire_failures_total`

---

## 2. Grafana

### 1) 실행

```bash
docker run -d --name grafana -p 3000:3000 grafana/grafana:latest
```

- UI: http://localhost:3000 (기본 로그인 admin / admin)

### 2) Prometheus 데이터 소스 추가

1. **Configuration** → **Data sources** → **Add data source**
2. **Prometheus** 선택
3. URL: `http://host.docker.internal:9090` (Docker 기준) 또는 `http://localhost:9090`
4. **Save & test**

### 3) 대시보드에서 보면 좋은 지표

- `ticketing_queue_waiting_count` — 콘서트별 대기 인원
- `ticketing_hold_created_total` — 홀드 생성 수
- `ticketing_holds_active_count` — 현재 활성 홀드 수 (Gauge)
- `ticketing_reservation_confirmed_total` — 콘서트별 예약 확정 수 (전환율)
- `ticketing_payment_completed_total` — 결제 완료 수
- `ticketing_hold_released_total{reason="confirmed|timeout|cancelled"}` — 홀드 해제 사유별
- `ticketing_refund_processed_total` — 환불 배치 처리 건수
- `ticketing_lock_acquire_failures_total` — 락 획득 실패
- `http_server_requests_*` — 요청 수·지연 (Actuator 기본 메트릭)

원하면 **Explore**에서 위 메트릭으로 쿼리 후 **Add to dashboard** 로 패널 추가.

---

## 3. EC2 등 원격 환경

- Prometheus `prometheus.yml`의 `targets`를 앱 서버 주소:8080으로 변경 (ALB 또는 인스턴스 직접).
- Grafana 데이터 소스 URL을 해당 Prometheus 주소로 설정.
- 방화벽에서 9090(Prometheus), 3000(Grafana) 접근 허용 여부 확인.
