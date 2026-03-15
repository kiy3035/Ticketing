# 부하 테스트 결과 요약

`load-tests/`의 k6 스크립트로 실행한 부하 테스트 결과를 정리한다.  
**목표**: 동시 사용자 수·RPS·응답 지연·에러율을 단계별로 측정하고, **knee point**(허용 기준을 넘기 시작하는 구간)를 기록한다.

## 실행 방법

```bash
# 대기열 위주 (인증 없음)
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 load-tests/queue-load-test.js

# EC2/실서버 대상 시
k6 run -e BASE_URL=http://your-alb-or-app-url -e CONCERT_ID=1 load-tests/queue-load-test.js
```

동시 사용자 수(VUs)·지속 시간은 스크립트 내 `options.scenarios` 또는 `--vus`, `--duration`으로 조정한다.

## 결과 기록 템플릿

아래 표를 채워 넣어 “동시 N명(또는 RPS)까지 검증” 수치를 문서화한다.

| 구간 | 동시 사용자(VUs) | RPS (평균) | http_req_duration p95 | http_req_failed | 비고 |
|------|------------------|------------|------------------------|-----------------|------|
| 1    | 100              | (측정값)   | (ms)                   | (%)             |      |
| 2    | 300              | (측정값)   | (ms)                   | (%)             |      |
| 3    | 500              | (측정값)   | (ms)                   | (%)             |      |
| 4    | 1000             | (측정값)   | (ms)                   | (%)             |      |
| 5    | 1500             | (측정값)   | (ms)                   | (%)             |      |

- **RPS**: 초당 요청 수 (k6 요약의 `http_reqs` / duration).
- **p95**: 응답 시간 95 백분위(ms). knee point 구간에서 급상승하는지 확인.
- **http_req_failed**: 요청 실패율. 목표 10~20% 이하 유지.

## Knee point 정리

- **검증 구간**: (예: 동시 500명 또는 RPS XXX까지 안정)
- **Knee point**: (예: 동시 1000명 구간에서 p95 2000ms 초과·에러율 15% 초과)
- **인프라**: (예: t3a.medium 1대 + t3.small 2대, ALB)

실제 측정 후 위 템플릿과 knee point 문단을 채워 두면 포트폴리오에서 “몇 명까지 검증했는지”를 명확히 보여줄 수 있다.

## 참고 지표 (Prometheus/Grafana)

- `ticketing_queue_waiting_count`: 콘서트별 대기 인원
- `ticketing_hold_created_total`: 홀드 생성 성공 수
- `ticketing_holds_active_count`: 현재 활성 홀드 수 (Gauge)
- `ticketing_reservation_confirmed_total`: 콘서트별 예약 확정 수 (전환율)
- `ticketing_payment_completed_total`: 결제 완료 수
- `ticketing_hold_released_total{reason="..."}`: 홀드 해제 사유별 (confirmed/timeout/cancelled)
- `ticketing_refund_processed_total`: 환불 배치 처리 건수
- `ticketing_lock_acquire_failures_total`: 락 획득 실패 수
- `ticketing_payment_complete_duration_seconds`: 결제 완료 소요 시간

전체 목록·설명은 [monitoring.md](monitoring.md) 참고.
