# 관리자 / 판매자 계정 & 역할

## 역할 구분

| 역할 | 접근 가능 화면 | 설명 |
|------|---------------|------|
| **USER** | `/app.html` | 일반 사용자. 콘서트 탐색·예매·결제 |
| **SELLER** | `/seller.html` | 판매자. 공연 등록·좌석 관리·예약/매출 조회 |
| **ADMIN** | `/admin.html` | 관리자. 통계·결제/사용자 조회·미판매 좌석 분석 |

로그인 시 역할에 따라 자동으로 해당 화면으로 리다이렉트된다.

## 관리자 대시보드 기능

- **통계**: 총 사용자 수, 총 예약 수, 오늘 결제 완료 수, 수단별 누적 매출(포인트/카드)
- **결제 관리**: COMPLETED 결제 내역 조회, 사용자명/결제키 검색
- **사용자 관리**: 전체 사용자 목록 조회, 사용자명/이메일 검색
- **미판매 좌석 통계**: 마감된 공연별 총 좌석/판매/미판매 집계 (기간 필터)

## 관리자 API 엔드포인트

모든 엔드포인트는 `@PreAuthorize("hasRole('ADMIN')")` 로 보호된다.

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| GET | `/api/admin/statistics/users` | 전체 사용자 수 |
| GET | `/api/admin/statistics/reservations` | 전체 예약 수 |
| GET | `/api/admin/statistics/payments` | 결제 통계 (오늘 완료 건수, 수단별 누적) |
| GET | `/api/admin/statistics/unsold-seats` | 마감 공연별 미판매 좌석 (페이징, 기간 필터) |
| GET | `/api/admin/payments` | 결제 내역 조회 (검색, 페이징) |
| GET | `/api/admin/users` | 사용자 목록 조회 (검색, 페이징) |

## 보안

- 비밀번호는 bcrypt 해시로 저장
- 모든 관리자 API는 Spring Security `@PreAuthorize` 로 권한 검증
- 세션은 Redis 에 저장되어 다중 인스턴스 환경에서도 동작
