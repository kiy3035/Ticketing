# API & 응답 스키마

## 공통 성공 응답
모든 정상 응답은 아래 형태로 래핑됩니다.

```json
{
  "success": true,
  "data": {},
  "message": "OK",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

## 공통 에러 응답

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "field: message",
  "path": "/api/...",
  "timestamp": "2026-01-25T10:00:00+09:00"
}
```

## 주요 API

### 콘서트/좌석
- `GET /api/concerts`
- `GET /api/concerts/{id}/seats`

### 홀드/예약
- `POST /api/holds`
- `POST /api/reservations`

### 알림
- `GET /api/notifications`
- `DELETE /api/notifications`

### 대기열
- `GET /api/queue/ticket?userId=...`
- `GET /api/queue/status?token=...`
- `GET /api/queue/count`

### 메트릭스
- `GET /api/metrics`

### 인증
- `POST /api/auth/signup`
- `GET /api/auth/me`
