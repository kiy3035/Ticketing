# 부하 테스트 (k6)

k6로 대기열·좌석 홀드·예약 확정 구간의 부하를 걸어 knee point 및 수용 인원을 측정한다.

## 사전 요구

- [k6](https://k6.io/docs/get-started/installation/) 설치
- 앱 실행 (기본 `http://localhost:8080`)
- 풀 플로우 테스트 시: 테스트용 사용자 1명 생성 (회원가입 후 ID/비밀번호 사용)

## 스크립트

| 파일 | 설명 |
|------|------|
| `queue-load-test.js` | 대기열 진입 → 입장 허용 폴링 → 좌석 조회까지. 인증 불필요. |
| `full-flow.js` | 대기열 필요 확인 → (필요 시) 진입/폴링 → 좌석 조회 → 홀드 → **포인트 결제**(완료 시 예약 확정). 인증 필요. |

full-flow는 결제를 **포인트 결제만** 사용 (요청 → 승인 → 완료). 카드는 위젯/리다이렉트 필요로 k6 자동화 불가.

## 실행 예

```bash
# 대기열 위주 (인증 없음, BASE_URL·CONCERT_ID만 지정)
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 queue-load-test.js

# 풀 플로우 (포인트 결제)
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 -e TEST_USER=loaduser -e TEST_PASS=loadpass load-tests/full-flow.js
```

Docker에서 앱이 호스트에서 돌 때:

```bash
k6 run -e BASE_URL=http://host.docker.internal:8080 -e CONCERT_ID=1 queue-load-test.js
```

## 지표

- `http_req_duration` p95: 응답 지연. knee point 구간에서 급상승하는지 확인.
- `http_req_failed`: 실패율. 목표 10~20% 이하.
- Prometheus/Grafana에서 `ticketing_queue_waiting_count`, `ticketing_hold_created_total`, `ticketing_holds_active_count`, `ticketing_reservation_confirmed_total`, `ticketing_payment_completed_total`, `ticketing_hold_released_total`, `ticketing_lock_acquire_failures_total` 등([docs/monitoring.md](../docs/monitoring.md))과 함께 보면 병목·전환율 파악에 유리하다.

## Knee point

동시 사용자 수(또는 RPS)를 단계적으로 올리면서, 응답 시간·에러율이 허용 기준을 넘기 시작하는 구간을 기록해 두면 “동시 N명(또는 RPS)까지 검증” 수치로 사용할 수 있다.
