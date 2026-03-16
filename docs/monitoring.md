# Prometheus & Grafana 모니터링

## 구성

- **Prometheus**: `/actuator/prometheus` 엔드포인트를 15초 간격으로 스크래핑
- **Grafana**: Prometheus 데이터소스 연결, 대시보드 패널 구성
- 앱에 `management.endpoints.web.exposure.include=health,metrics,prometheus` 설정 필요

## 커스텀 메트릭

| 메트릭 이름 | 타입 | 설명 |
|------------|------|------|
| `ticketing_queue_waiting_count` | Gauge | 콘서트별 대기 인원 |
| `ticketing_hold_created_total` | Counter | 홀드 생성 수 |
| `ticketing_holds_active_count` | Gauge | 현재 활성 홀드 수 |
| `ticketing_reservation_confirmed_total` | Counter | 콘서트별 예약 확정 수 |
| `ticketing_payment_completed_total` | Counter | 결제 완료 수 |
| `ticketing_hold_released_total` | Counter | 홀드 해제 사유별 (confirmed/timeout/cancelled) |
| `ticketing_refund_processed_total` | Counter | 환불 배치 처리 건수 |
| `ticketing_lock_acquire_failures_total` | Counter | 락 획득 실패 수 |
| `ticketing_payment_complete_duration_seconds` | Histogram | 결제 완료 소요 시간 |

Actuator 기본 메트릭 (`http_server_requests_*`)으로 요청 수·지연도 함께 확인한다.

## EC2 등 원격 환경

- `prometheus.yml`의 `targets`를 앱 서버 주소:8080으로 변경
- Grafana 데이터소스 URL을 Prometheus 주소로 설정
- 방화벽: 9090(Prometheus), 3000(Grafana) 포트 허용
