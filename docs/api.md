# API 명세

## 공통 응답 구조

- **성공**: `{ "success": true, "data": {}, "message": "OK", "timestamp": "..." }`
- **에러**: `{ "status": 400, "error": "Bad Request", "message": "...", "path": "...", "timestamp": "..." }`
- 상태 코드: 200, 201, 204, 400, 401, 403, 404, 409, 429, 500

## 인증 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | `/api/auth/signup` | 회원가입 (username, password) | 불필요 |
| GET | `/api/auth/me` | 현재 사용자 정보 조회 | 필요 |

인증 방식: Spring Security 세션 기반. 로그인 성공 시 세션 쿠키 발급, Redis 저장 (TTL 30분).

인증 불필요 경로: `/`, `/login.html`, `/signup.html`, `POST /login`, `POST /logout`, `POST /api/auth/signup`, `/api/queue/**`

## 콘서트 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| GET | `/api/concerts?query=&category=` | 콘서트 목록 조회 (Redis 캐시 5분) | 필요 |
| GET | `/api/concerts/{concertId}/seats` | 좌석 목록 조회 (DB + Redis 홀드 오버레이) | 필요 |

- 카테고리: `ALL`, `IDOL`, `BALLAD`, `ROCK`, `HIPHOP`, `JAZZ`, `CLASSICAL`
- 콘서트 상태: `UPCOMING`, `ONGOING`, `COMPLETED`, `CANCELLED`
- 좌석 상태: `AVAILABLE`, `HELD` (Redis), `RESERVED` (DB)

## 대기열 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | `/api/queue/enter?concertId={id}` | 대기열 진입 (토큰 발급, 중복 방지) | 필요 |
| GET | `/api/queue/status?token=&concertId=` | 순번·대기인원·입장허용 조회 (2초 폴링) | 필요 |
| GET | `/api/queue/allowed?token=` | 입장 허용 여부 확인 | 필요 |
| GET | `/api/queue/count?concertId=` | 대기인원 수 조회 | 필요 |
| GET | `/api/queue/required?concertId=` | 대기열 필요 여부 (유동 활성화) | 필요 |
| DELETE | `/api/queue/exit?token=&concertId=` | 대기열 나가기 | 필요 |

## 홀드 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | `/api/holds` | 좌석 홀드 생성 (분산 락 → Lua 원자적 생성 → Kafka 이벤트) | 필요 |
| DELETE | `/api/holds/{holdToken}` | 홀드 취소 | 필요 |

- 409: 이미 예약/홀드된 좌석
- 429: 락 획득 실패 (좌석 사용 중)

## 예약 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| GET | `/api/reservations/me` | 내 예약 내역 조회 | 필요 |

예약 생성은 별도 API 없이 `POST /api/payments/{paymentKey}/complete` 내부에서 자동 수행된다.

## 결제 API

포인트 기반 Mock 결제. 흐름: READY → APPROVED → COMPLETED. 카드 결제 시 토스페이먼츠 샌드박스 연동.

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | `/api/payments/request` | 결제 요청 생성 (holdToken, paymentMethod) → READY | 필요 |
| POST | `/api/payments/{paymentKey}/approve` | 결제 승인 (포인트 차감 또는 토스 승인) → APPROVED | 필요 |
| POST | `/api/payments/{paymentKey}/complete` | 결제 완료 + 예약 확정 → COMPLETED | 필요 |
| POST | `/api/payments/{paymentKey}/cancel` | 결제 취소 → CANCELED | 필요 |
| GET | `/api/payments/{paymentKey}` | 결제 조회 | 필요 |
| GET | `/api/payments/toss-client-key` | 토스 클라이언트 키 조회 (프론트용) | 필요 |

취소 공연 환불은 백그라운드 배치로만 동작한다 (사용자 API 없음).

## 알림 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| GET | `/api/notifications` | 알림 목록 조회 (최대 50개, 폴링용) | 필요 |
| GET | `/api/notifications/stream` | SSE 실시간 알림 스트림 (30분 타임아웃) | 필요 |
| DELETE | `/api/notifications` | 알림 전체 삭제 | 필요 |

## 지표 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| GET | `/api/metrics` | 접속자 수, 콘서트 수, 예약 수 | 필요 |

## 관리자 API (ADMIN)

모든 엔드포인트 `@PreAuthorize("hasRole('ADMIN')")`. 상세는 [admin-setup.md](admin-setup.md) 참고.

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| GET | `/api/admin/statistics/users` | 전체 사용자 수 |
| GET | `/api/admin/statistics/reservations` | 전체 예약 수 |
| GET | `/api/admin/statistics/payments` | 결제 통계 (오늘 완료, 수단별 누적) |
| GET | `/api/admin/statistics/unsold-seats` | 마감 공연별 미판매 좌석 |
| GET | `/api/admin/payments?search=&page=&size=` | 결제 내역 조회 |
| GET | `/api/admin/users?search=&page=&size=` | 사용자 목록 조회 |
