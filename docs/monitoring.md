# Prometheus & Grafana 모니터링

## 커스텀 비즈니스 메트릭

| 메트릭 | 타입 | 설명 |
|--------|------|------|
| `ticketing_queue_waiting_count` | Gauge | 콘서트별 대기열 인원 |
| `ticketing_hold_created_total` | Counter | 홀드 생성 성공 수 |
| `ticketing_hold_conflict_total` | Counter | Redis Lua false → 409 (좌석 경합 감지) |
| `ticketing_holds_active_count` | Gauge | 현재 활성 홀드 수 |
| `ticketing_lock_acquire_failures_total` | Counter | 락 획득 실패 수 (429 원인) |
| `ticketing_reservation_confirmed_total` | Counter | 콘서트별 예약 확정 수 |
| `ticketing_payment_completed_total` | Counter | 결제 완료 수 |
| `ticketing_payment_complete_duration_seconds` | Histogram | 결제 완료 소요 시간 |
| `ticketing_hold_released_total` | Counter | 홀드 해제 사유별 (confirmed/timeout/cancelled) |

## Grafana Golden Signals (PromQL)

```promql
-- Traffic: 전체 RPS
sum(rate(http_server_requests_seconds_count{application="ticketing"}[30s]))

-- Latency: HTTP p95
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="ticketing"}[1m])) by (le))

-- Saturation: DB 커넥션 풀
hikaricp_connections_active{application="ticketing"}
hikaricp_connections_pending{application="ticketing"}

-- 비즈니스: 홀드 경합 (409 직전)
sum(rate(ticketing_hold_conflict_total{application="ticketing"}[1m]))
```
