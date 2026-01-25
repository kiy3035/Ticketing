# Redis/Kafka/세션 구조

## Redis 키 구조
- 홀드: `hold:seat:{seatId}`, `hold:token:{holdToken}`, `hold:expires`
- 알림: `notify:user:{userId}` (최대 50개, TTL 7일)
- 대기열: `queue:rank`, `queue:token:{token}`
- 접속자: `active:users` (5분 윈도우)
- 세션: `ticketing:sessions:*` (Spring Session)

## Kafka 이벤트
- `HOLD_CREATED`, `HOLD_CANCELED`, `HOLD_EXPIRED`, `RESERVATION_CONFIRMED`

## 세션 관리 (Redis)
- 세션 저장소: Redis
- 네임스페이스: `ticketing:sessions`
- 만료 시간: `server.servlet.session.timeout` (기본 30분)
- 직렬화: JSON

## 활성 사용자 추적
로그인/로그아웃 시점에 Redis ZSet으로 실시간 접속자를 기록합니다.
