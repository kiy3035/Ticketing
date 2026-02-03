# API & 응답 스키마

## 공통 응답 구조

### 성공 응답
모든 정상 응답은 `ApiResponse` 래퍼로 감싸집니다.

```json
{
  "success": true,
  "data": {},
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

### 에러 응답
에러 발생 시 아래 형태로 반환됩니다.

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "field: message",
  "path": "/api/...",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**HTTP 상태 코드**:
- `200 OK`: 정상 처리
- `201 Created`: 리소스 생성 성공
- `204 No Content`: 정상 처리 (응답 본문 없음)
- `400 Bad Request`: 잘못된 요청
- `401 Unauthorized`: 인증 필요
- `403 Forbidden`: 권한 없음
- `404 Not Found`: 리소스 없음
- `409 Conflict`: 충돌 (중복, 만료 등)
- `429 Too Many Requests`: 너무 많은 요청 (락 획득 실패)
- `500 Internal Server Error`: 서버 오류

## 인증 API

### 회원가입
```http
POST /api/auth/signup
Content-Type: application/json
```

**요청 본문**:
```json
{
  "username": "user123",
  "password": "password123"
}
```

**응답** (201 Created):
```json
{
  "success": true,
  "data": {
    "id": 1,
    "username": "user123",
    "createdAt": "2026-01-25T10:00:00+09:00"
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**에러 케이스**:
- `400 Bad Request`: 사용자명/비밀번호 형식 오류
- `409 Conflict`: 사용자명 중복

### 현재 사용자 정보 조회
```http
GET /api/auth/me
Authorization: (세션 쿠키)
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "id": 1,
    "username": "user123",
    "createdAt": "2026-01-25T10:00:00+09:00"
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**에러 케이스**:
- `401 Unauthorized`: 인증되지 않음

## 콘서트 API

### 콘서트 목록 조회
```http
GET /api/concerts?query={검색어}&category={카테고리}
Authorization: (세션 쿠키)
```

**쿼리 파라미터**:
- `query` (optional): 검색어 (제목/장소)
- `category` (optional): 카테고리 (`ALL`, `IDOL`, `BALLAD`, `ROCK`, `HIPHOP`, `JAZZ`, `CLASSICAL`)

**응답** (200 OK):
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "title": "Winter Beats 2026",
      "venue": "올림픽공원 KSPO DOME",
      "startAt": "2026-02-02T04:00:00+09:00",
      "endAt": "2026-02-02T07:00:00+09:00",
      "status": "OPEN",
      "category": "IDOL"
    }
  ],
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**캐싱**: Redis 캐시 (5분 TTL)

### 콘서트별 좌석 목록 조회
```http
GET /api/concerts/{concertId}/seats
Authorization: (세션 쿠키)
```

**경로 변수**:
- `concertId`: 콘서트 ID

**응답** (200 OK):
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "section": "A",
      "seatNo": "A-1",
      "price": 150000,
      "status": "AVAILABLE"
    },
    {
      "id": 2,
      "section": "A",
      "seatNo": "A-2",
      "price": 150000,
      "status": "HELD"
    },
    {
      "id": 3,
      "section": "B",
      "seatNo": "B-1",
      "price": 100000,
      "status": "RESERVED"
    }
  ],
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**좌석 상태**:
- `AVAILABLE`: 예약 가능
- `HELD`: 임시 홀드 (Redis에서 조회)
- `RESERVED`: 예약 완료 (DB에서 조회)

**에러 케이스**:
- `404 Not Found`: 콘서트 없음

## 대기열 API

### 대기열 진입
```http
POST /api/queue/enter?concertId={concertId}
Authorization: (세션 쿠키)
```

**쿼리 파라미터**:
- `concertId`: 콘서트 ID (필수)

**응답** (201 Created):
```json
{
  "success": true,
  "data": {
    "token": "c13bb5d9-6b59-467e-80ab-6be37848c9cb",
    "rank": 654,
    "totalWaiting": 654
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**동작**:
1. 기존 토큰 확인 (중복 진입 방지)
2. 새 토큰 발급 (UUID)
3. Redis ZSet에 토큰 추가 (`queue:concert:{concertId}`)
4. 토큰 정보 저장 (`queue:token:{token}`, TTL 30분)
5. 순번 및 대기인원 수 반환

**에러 케이스**:
- `400 Bad Request`: 콘서트 ID 없음
- `404 Not Found`: 콘서트 없음

### 대기열 상태 조회
```http
GET /api/queue/status?token={token}&concertId={concertId}
Authorization: (세션 쿠키)
```

**쿼리 파라미터**:
- `token`: 대기열 토큰 (필수)
- `concertId`: 콘서트 ID (필수)

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "token": "c13bb5d9-6b59-467e-80ab-6be37848c9cb",
    "rank": 654,
    "totalWaiting": 654,
    "isAllowed": false
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**동작**:
1. Redis ZSet에서 순번 조회 (`ZRANK`)
2. 대기인원 수 조회 (`ZCARD`)
3. 입장 허용 여부 확인 (`GET queue:allowed:{token}`)

**에러 케이스**:
- `400 Bad Request`: 토큰 또는 콘서트 ID 없음
- `404 Not Found`: 토큰 없음

### 입장 허용 여부 확인
```http
GET /api/queue/allowed?token={token}
Authorization: (세션 쿠키)
```

**쿼리 파라미터**:
- `token`: 대기열 토큰 (필수)

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "allowed": true,
    "concertId": 1
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**에러 케이스**:
- `400 Bad Request`: 토큰 없음

### 대기인원 수 조회
```http
GET /api/queue/count?concertId={concertId}
Authorization: (세션 쿠키)
```

**쿼리 파라미터**:
- `concertId`: 콘서트 ID (필수)

**응답** (200 OK):
```json
{
  "success": true,
  "data": 654,
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

### 대기열 나가기
```http
DELETE /api/queue/exit?token={token}&concertId={concertId}
Authorization: (세션 쿠키)
```

**쿼리 파라미터**:
- `token`: 대기열 토큰 (필수)
- `concertId`: 콘서트 ID (필수)

**응답** (204 No Content)

**동작**:
1. Redis ZSet에서 토큰 제거
2. 토큰 정보 삭제
3. 입장 허용 상태 삭제

## 홀드 API

### 홀드 생성
```http
POST /api/holds
Content-Type: application/json
Authorization: (세션 쿠키)
```

**요청 본문**:
```json
{
  "concertId": 1,
  "seatId": 1
}
```

**응답** (201 Created):
```json
{
  "success": true,
  "data": {
    "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "expiresAt": "2026-01-25T10:05:00+09:00"
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**동작**:
1. 좌석 조회 및 검증
2. 분산 락 획득 (`lock:seat:{seatId}`)
3. 좌석 상태 확인 (RESERVED 체크)
4. Lua 스크립트로 원자적 홀드 생성
5. Kafka로 `HOLD_CREATED` 이벤트 발행
6. 락 해제

**에러 케이스**:
- `400 Bad Request`: 요청 본문 오류
- `404 Not Found`: 좌석 없음
- `409 Conflict`: 좌석이 이미 예약됨 또는 홀드됨
- `429 Too Many Requests`: 락 획득 실패 (좌석이 사용 중)

### 홀드 취소
```http
DELETE /api/holds/{holdToken}
Authorization: (세션 쿠키)
```

**경로 변수**:
- `holdToken`: 홀드 토큰

**응답** (204 No Content)

**동작**:
1. 홀드 조회 및 검증
2. 사용자 일치 확인
3. Lua 스크립트로 원자적 홀드 해제
4. Kafka로 `HOLD_CANCELED` 이벤트 발행

**에러 케이스**:
- `404 Not Found`: 홀드 없음
- `409 Conflict`: 홀드 소유자 불일치

## 예약 API

### 예약 확정
```http
POST /api/reservations
Content-Type: application/json
Authorization: (세션 쿠키)
```

**요청 본문**:
```json
{
  "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**응답** (201 Created):
```json
{
  "success": true,
  "data": {
    "id": 1,
    "concertId": 1,
    "seatId": 1,
    "userId": "user123",
    "status": "CONFIRMED",
    "reservedAt": "2026-01-25T10:00:00+09:00"
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**동작**:
1. 홀드 조회 및 검증 (만료 시간, 사용자 일치)
2. 분산 락 획득
3. 홀드 유효성 재확인
4. DB 트랜잭션 시작
5. 좌석 상태를 RESERVED로 변경
6. 예약 레코드 생성
7. 홀드 해제
8. Kafka로 `RESERVATION_CONFIRMED` 이벤트 발행
9. 트랜잭션 커밋
10. 락 해제

**에러 케이스**:
- `400 Bad Request`: 요청 본문 오류
- `404 Not Found`: 홀드 없음
- `409 Conflict`: 홀드 만료 또는 좌석이 이미 예약됨
- `429 Too Many Requests`: 락 획득 실패

### 예약 내역 조회
```http
GET /api/reservations
Authorization: (세션 쿠키)
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "concertTitle": "Winter Beats 2026",
      "venue": "올림픽공원 KSPO DOME",
      "startAt": "2026-02-02T04:00:00+09:00",
      "endAt": "2026-02-02T07:00:00+09:00",
      "section": "A",
      "seatNo": "A-1",
      "price": 150000,
      "status": "CONFIRMED",
      "reservedAt": "2026-01-25T10:00:00+09:00"
    }
  ],
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

## 결제 API (Mock Payment)

결제는 실제 PG 연동이 아닌 **포인트 기반 Mock 결제**로 동작합니다.  
흐름은 `READY → APPROVED → COMPLETED`이며, 필요 시 `CANCELED`로 전환됩니다.

### 결제 요청 생성 (READY)
```http
POST /api/payments/request
Content-Type: application/json
Authorization: (세션 쿠키)
```

**요청 본문**:
```json
{
  "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**응답** (201 Created):
```json
{
  "success": true,
  "data": {
    "paymentKey": "e8f1b1a4-1a0e-4b1c-9f59-1d4ccfab1f4b",
    "status": "READY",
    "amount": 150000,
    "reservationId": null,
    "approvedAt": null,
    "completedAt": null,
    "canceledAt": null
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

### 결제 승인 (APPROVED)
```http
POST /api/payments/{paymentKey}/approve
Authorization: (세션 쿠키)
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "paymentKey": "e8f1b1a4-1a0e-4b1c-9f59-1d4ccfab1f4b",
    "status": "APPROVED",
    "amount": 150000,
    "approvedAt": "2026-01-25T10:01:00+09:00"
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:01:00+09:00"
}
```

**에러 케이스**:
- `409 Conflict`: 포인트 부족, 이미 취소됨

### 결제 완료 (COMPLETED)
```http
POST /api/payments/{paymentKey}/complete
Authorization: (세션 쿠키)
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "paymentKey": "e8f1b1a4-1a0e-4b1c-9f59-1d4ccfab1f4b",
    "status": "COMPLETED",
    "amount": 150000,
    "reservationId": 123,
    "completedAt": "2026-01-25T10:02:00+09:00"
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:02:00+09:00"
}
```

**동작**:
1. 결제 상태가 APPROVED인지 검증
2. 예약 확정 처리 (`/api/reservations` 내부 호출)
3. 결제 상태 COMPLETED로 변경

### 결제 취소 (CANCELED)
```http
POST /api/payments/{paymentKey}/cancel
Authorization: (세션 쿠키)
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "paymentKey": "e8f1b1a4-1a0e-4b1c-9f59-1d4ccfab1f4b",
    "status": "CANCELED",
    "canceledAt": "2026-01-25T10:03:00+09:00"
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:03:00+09:00"
}
```

### 결제 조회
```http
GET /api/payments/{paymentKey}
Authorization: (세션 쿠키)
```

## 알림 API

### 알림 목록 조회 (폴링용)
```http
GET /api/notifications
Authorization: (세션 쿠키)
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "type": "HOLD_EXPIRED",
        "message": "예약이 만료되었습니다. A구역 A-1",
        "createdAt": "2026-01-25T10:00:00+09:00"
      }
    ],
    "unreadCount": 1
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**동작**:
1. Redis List에서 알림 조회 (`notify:user:{userId}`)
2. 최대 50개 반환
3. 읽지 않은 알림 수 계산

### SSE 실시간 알림 스트림
```http
GET /api/notifications/stream
Authorization: (세션 쿠키)
Accept: text/event-stream
```

**응답** (200 OK, `text/event-stream`):
```
event: notification
data: {"type":"HOLD_EXPIRED","message":"예약이 만료되었습니다. A구역 A-1","createdAt":"2026-01-25T10:00:00+09:00"}

event: notification
data: {"type":"RESERVATION_CONFIRMED","message":"결제가 완료되었습니다. A구역 A-1","createdAt":"2026-01-25T10:00:00+09:00"}
```

**동작**:
1. 사용자별 SSE 연결 생성 (`SseEmitter`)
2. 연결 유지 (30분 타임아웃)
3. Kafka 이벤트 수신 시 해당 사용자에게 즉시 전송
4. 연결 종료 시 자동 정리

**이벤트 타입**:
- `notification`: 알림 이벤트

**에러 케이스**:
- `401 Unauthorized`: 인증되지 않음

### 알림 전체 삭제
```http
DELETE /api/notifications
Authorization: (세션 쿠키)
```

**응답** (204 No Content)

**동작**:
1. Redis List에서 모든 알림 삭제 (`notify:user:{userId}`)

## 지표 API

### 지표 조회
```http
GET /api/metrics
Authorization: (세션 쿠키)
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "activeUsers": 42,
    "totalConcerts": 10,
    "openConcerts": 5,
    "totalReservations": 1234
  },
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

**동작**:
1. Redis ZSet에서 활성 사용자 수 조회 (`active:users`, 5분 윈도우)
2. MySQL에서 콘서트 수 조회
3. MySQL에서 예약 수 조회

## 인증 방식

### 세션 기반 인증
- Spring Security의 세션 기반 인증 사용
- 로그인 성공 시 세션 쿠키 발급
- 세션은 Redis에 저장 (TTL 30분)
- 모든 API 요청 시 세션 쿠키 필요 (일부 제외)

### 인증 불필요한 엔드포인트
- `GET /`, `/index.html`
- `GET /login.html`, `/signup.html`
- `POST /login`, `/logout`
- `POST /api/auth/signup`
- `POST /api/queue/**` (부하 테스트용)

## 요청/응답 예시

### cURL 예시

**콘서트 목록 조회**:
```bash
curl -X GET "http://localhost:8080/api/concerts?category=IDOL" \
  -H "Cookie: JSESSIONID=..."
```

**대기열 진입**:
```bash
curl -X POST "http://localhost:8080/api/queue/enter?concertId=1" \
  -H "Cookie: JSESSIONID=..."
```

**홀드 생성**:
```bash
curl -X POST "http://localhost:8080/api/holds" \
  -H "Content-Type: application/json" \
  -H "Cookie: JSESSIONID=..." \
  -d '{"concertId":1,"seatId":1}'
```

**SSE 연결**:
```bash
curl -N "http://localhost:8080/api/notifications/stream" \
  -H "Cookie: JSESSIONID=..."
```

## 성능 고려사항

### 캐싱
- 콘서트 목록: Redis 캐시 (5분 TTL)
- 대기열 순번: Redis ZSet (O(log N))

### 배치 처리
- 대기열 처리: 2초마다 상위 50명 일괄 처리
- 홀드 만료: 60초마다 최대 200개 일괄 처리

### 연결 풀링
- Redis: Lettuce 연결 풀 (최대 20개)
- MySQL: HikariCP 연결 풀

### 폴링 최적화
- 대기열 상태: 2초마다 폴링
- 알림 목록: 30초마다 폴링 (SSE 백업용)
