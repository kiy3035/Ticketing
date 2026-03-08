# 관리자 계정 설정 가이드

## 개요

ADMIN 권한을 가진 사용자가 관리자 대시보드(`/admin.html`)에 접근할 수 있습니다.

---

## ADMIN 사용자 생성 방법

### 방법 1: MySQL 직접 쿼리 (권장)

1. MySQL에 접속합니다:
```bash
mysql -u root -p <password> ticketing
```

2. 다음 쿼리를 실행하여 ADMIN 사용자를 생성합니다:
```sql
-- admin 사용자 생성 (비밀번호: admin123)
INSERT INTO users (username, pw, email, phone, noti_type, role, point, created_at) 
VALUES (
  'admin',
  '$2a$10$slYQmyNdGzIn9KqvkXmQ4eZ7Gz8LW5ycLLdXnz.HcNNx/0zL8pDOi',  -- bcrypt 해시된 "admin123"
  'admin@concert.com',
  '01000000000',
  'sms',
  'ADMIN',
  1000000,
  NOW()
);
```

> **주의**: 위의 해시는 `admin123` 문자열의 bcrypt 해시입니다. 실제로 사용하려면 Java에서 생성한 해시를 사용하세요.

### 방법 2: Java를 사용한 해시 생성

Spring Security의 `PasswordEncoder`를 사용하여 안전한 비밀번호 해시를 생성합니다:

```java
// Spring Boot 앱 내에서 실행
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
String rawPassword = "yourAdminPassword";  // 원하는 비밀번호
String hashedPassword = encoder.encode(rawPassword);
System.out.println(hashedPassword);  // MySQL에 이 값을 사용
```

그 후 다음 쿼리를 실행합니다:
```sql
INSERT INTO users (username, pw, email, phone, noti_type, role, point, created_at) 
VALUES (
  'admin',
  'bcrypt_hashed_password_here',  -- 위에서 생성한 해시
  'admin@concert.com',
  '01000000000',
  'sms',
  'ADMIN',
  1000000,
  NOW()
);
```

---

## 관리자 대시보드 접근

1. ADMIN 사용자로 로그인합니다 (로그인 페이지: `/login.html`)
2. 로그인 성공 후 자동으로 관리자 대시보드(`/admin.html`)로 리다이렉팅됩니다

---

## 관리자 대시보드 기능

### 1. 통계 탭
- 총 사용자 수
- 총 예약 수
- 오늘 결제 완료 수
- 총 매출액

### 2. 결제 관리 탭
- 최근 결제 내역 조회
- 사용자명 또는 결제 번호로 검색
- 결제 상태(READY, APPROVED, COMPLETED, CANCELED) 표시

### 3. 사용자 관리 탭
- 전체 사용자 목록 조회
- 사용자명 또는 이메일로 검색
- 사용자 역할(ADMIN/USER) 표시
- 가입 일시 확인

---

## API 엔드포인트 (ADMIN 전용)

모든 엔드포인트는 `@PreAuthorize("hasRole('ADMIN')")` 보호 됩니다.

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| GET | `/api/admin/statistics/users` | 전체 사용자 수 |
| GET | `/api/admin/statistics/reservations` | 전체 예약 수 |
| GET | `/api/admin/statistics/payments` | 결제 통계 (오늘, 총액) |
| GET | `/api/admin/payments?search={}&page={}&size={}` | 결제 내역 조회 (검색 가능) |
| GET | `/api/admin/users?search={}&page={}&size={}` | 사용자 목록 조회 (검색 가능) |

---

## 일반 사용자로 전환

관리자 대시보드에서 "일반 사용자로 전환" 링크를 클릭하면 홈 페이지(`/app.html`)로 이동합니다.

---

## 보안 고려사항

1. **비밀번호 관리**: 기본 `admin123`은 반드시 변경하세요
2. **권한 검사**: AdminController의 모든 메서드는 ADMIN 권한을 확인합니다
3. **감사 로그**: 관리자 작업에 대한 감사 로그는 추후 추가 예정입니다
4. **두 가지 인증**: 추후 2FA(Two-Factor Authentication) 추가를 권장합니다

---

## 자주 묻는 질문

### Q: ADMIN 권한과 USER 권한의 차이점은?
- **ADMIN**: 관리자 대시보드(`/admin.html`)에 접근 가능하며, 통계 및 관리 기능 사용 가능
- **USER**: 일반 사용자로 콘서트 예매(`/app.html`)만 가능

### Q: 기존 사용자를 ADMIN으로 변경하려면?
다음 쿼리를 실행합니다:
```sql
UPDATE users SET role = 'ADMIN' WHERE username = 'username';
```

### Q: ADMIN 사용자를 일반 사용자로 변경하려면?
```sql
UPDATE users SET role = 'USER' WHERE username = 'username';
```

---

## 판매자(SELLER) 계정 생성

판매자 대시보드(`/seller.html`)는 **SELLER** 역할을 가진 사용자만 접근할 수 있습니다. 로그인 시 자동으로 `/seller.html`로 리다이렉트됩니다.

### SELLER 사용자 생성 (MySQL)

```sql
-- seller / admin123 (위와 동일한 bcrypt 해시 사용 시)
INSERT INTO users (username, pw, email, phone, noti_type, role, point, created_at)
VALUES (
  'seller',
  '$2a$10$slYQmyNdGzIn9KqvkXmQ4eZ7Gz8LW5ycLLdXnz.HcNNx/0zL8pDOi',
  'seller@concert.com',
  '01000000001',
  'sms',
  'SELLER',
  0,
  NOW()
);
```

- 판매자로 로그인 후 **공연 등록** → **좌석 일괄 등록** → 일반 사용자가 해당 공연을 예매할 수 있습니다.
- 등록한 공연만 목록에 표시되며, 예약·매출을 조회할 수 있습니다.

---

## 추후 개선 사항

- [ ] 관리 인터페이스에서 사용자 역할 변경 기능
- [ ] 관리자 작업 감사 로그
- [ ] 결제/예약 취소 기능
- [ ] 공연 생성/수정/삭제 기능
- [ ] 통계 차트 및 그래프
- [ ] 두 가지 인증(2FA) 지원
