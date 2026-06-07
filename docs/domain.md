# 도메인 컨텍스트 (핵심 흐름 · 용어 · 규칙)

> 이 문서는 `CLAUDE.md`에서 분리된 상세 참조다. 매 세션 자동 적재를 피하려고 `CLAUDE.md`에는
> 경로만 남긴다(`@` import 아님). 도메인(대기열·홀드·결제·예약·알림) 작업 시 이 파일을 읽는다.

## 핵심 흐름 (한 줄)
```
로그인(JWT) → 대기열 진입(토큰·순번) → 입장 허용(스케줄러 배치)
→ 좌석 홀드(분산 락 + 5분 TTL) → 결제 완료 → 예약 확정(DB outbox → Kafka) → SSE 알림
```

## 용어 · 규칙
- **대기열(Queue)**: Redis ZSet 기반, O(log N) 순번. 토큰 TTL + 정리 스케줄러. "패턴 B 유동 활성화"(임계치 초과 시만 대기열 페이지).
- **좌석 홀드(Hold)**: 락 키 `lock:seat:{seatId}`로 동시성 제어. 홀드 TTL 5분(결제 진입 시 20분 연장). 만료 ZSet `hold:expires` + 스케줄러 정리.
- **결제(Payment)**: 실결제 없는 Mock 포인트 결제. `READY → APPROVED → COMPLETED / CANCELED`. 동일 홀드 토큰 재요청 시 동일 결제 반환(멱등).
- **예약 확정**: 결제 완료 → DB 기록 + **같은 트랜잭션으로 outbox 적재** → 스케줄러가 Kafka로 `RESERVATION_CONFIRMED` 발행 → DB 커밋 후 리스너가 Redis 홀드 제거. (별도 예약 확정 API 없음)
- **알림**: Kafka 이벤트 → SSE 실시간 전달(폴링 백업). 토픽 `ticketing.seat-hold-events`, 이벤트 `HOLD_CREATED/CANCELED/EXPIRED`, `RESERVATION_CONFIRMED`.
- **JWT**: Access 30분 / Refresh 14일(HS256). 로그아웃 시 Access jti → Redis 블랙리스트, Refresh → DB revoke.
- **메트릭**: `ticketing_*`(대기열/홀드/경합/결제/락 실패 등) — `docs/monitoring.md` 참고.
- 부하 테스트 결과 해석은 항상 **knee point / bottleneck 관점**.
