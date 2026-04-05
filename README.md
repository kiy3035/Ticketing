# 콘서트 예매 시스템 (Concert Ticketing System)

백엔드 중심의 콘서트 예매 시스템입니다. Redis(세션/홀드/락/캐시)와 Kafka(이벤트 스트리밍)를 활용해 **동시성·정합성·확장성**을 고려한 설계를 구현했습니다. 로그인 기반 프론트까지 포함한 MVP이며, 실무 시나리오에 맞춘 흐름을 제공합니다.

## 🎯 프로젝트 개요

대규모 트래픽이 예상되는 콘서트 예매 시나리오를 고려하여 설계된 시스템입니다. 특히 **동시 접속자 폭증**, **좌석 중복 예약 방지**, **실시간 알림** 등의 문제를 해결하기 위해 다양한 기술과 패턴을 적용했습니다.

### 핵심 가치
- **공정성**: 대기열 시스템으로 선착순 공정한 순번 관리
- **정합성**: 분산 락과 Redis TTL로 중복 예약 방지
- **실시간성**: SSE를 통한 즉각적인 알림 전달
- **확장성**: Redis 기반 세션/데이터 외부화로 수평 확장 가능

## 🚀 포트폴리오 핵심 요약

### 1. 대기열 시스템 (Queue System)
- **Redis ZSet 기반**: 콘서트별 대기열로 대규모 트래픽 처리
- **O(log N) 순번 조회**: ZSet의 RANK 연산으로 효율적인 순번 관리
- **배치 처리**: 스케줄러가 주기적으로 상위 N명을 입장 허용하여 서버 부하 분산
- **패턴 B (유동 활성화)**: 대기 인원이 `activation-threshold` 초과일 때만 대기열 페이지 진입, 이하면 바로 좌석 페이지 ([설정](src/main/resources/application.properties): `ticketing.queue.activation-threshold`)
- **즉시 입장**: 대기 인원이 적고 좌석이 있으면 진입 시 즉시 입장 허용 (`immediate-allow-threshold`)
- **토큰 기반 인증**: UUID 토큰으로 사용자 식별 및 중복 진입 방지
- **만료 정리**: 토큰 TTL + 정리 스케줄러로 유령 대기열 자동 제거

### 2. 좌석 홀드/만료 시스템 (Hold System)
- **Redis TTL + 분산 락**: 경쟁 상태 해결 및 중복 홀드 방지. 락 키 `lock:seat:{seatId}`, TTL은 설정으로 조정 ([docs/concurrency.md](docs/concurrency.md))
- **홀드 TTL**: 10분(600초, `ticketing.hold.ttl-seconds`). 결제 진행 시 연장 설정 가능
- **자동 만료 처리**: 스케줄러가 만료된 홀드를 스캔하여 자동 정리
- **이벤트 기반 알림**: Kafka로 만료 이벤트 발행 후 SSE로 실시간 전달

### 3. Mock 결제 시스템 (Point Payment)
- **실결제 없이 PG 유사 흐름 구현**: READY → APPROVED → COMPLETED / CANCELED
- **포인트 차감 기반 승인**: 가입 시 지급된 포인트로 결제 시뮬레이션
- **중복 요청 안전**: 동일 홀드 토큰 재요청 시 동일 결제 반환

### 3. 이벤트 스트리밍 (Event Streaming)
- **Kafka 기반**: HOLD/RESERVATION 이벤트를 비동기로 처리
- **이벤트 타입**: `HOLD_CREATED`, `HOLD_CANCELED`, `HOLD_EXPIRED`, `RESERVATION_CONFIRMED`
- **이벤트 소비**: Kafka Consumer가 이벤트를 수신하여 알림 저장 및 SSE 전송

### 4. 실시간 알림 시스템 (Real-time Notification)
- **Server-Sent Events (SSE)**: Kafka 이벤트를 클라이언트에 즉시 전달
- **폴링 백업**: SSE 연결 실패 시를 대비한 폴링 메커니즘 (30초 주기)
- **사용자별 연결 관리**: `SseNotificationService`에서 사용자별 SSE 연결 관리

### 5. 세션 외부화 (Session Externalization)
- **Spring Session + Redis**: 세션 데이터를 Redis에 저장하여 다중 인스턴스 확장 대응
- **세션 만료**: 30분 TTL로 자동 만료 처리
- **JSON 직렬화**: JavaTime 모듈 지원으로 Instant 타입 직렬화

### 6. 캐시 계층 (Cache Layer)
- **서버 캐시**: Redis를 활용한 콘서트 목록 캐싱 (5분 TTL)
- **클라이언트 캐시**: 카테고리 전환 시 30초 TTL 메모리 캐시로 즉시 반응
- **캐시 전략**: `@Cacheable`, `@CacheEvict` 어노테이션 기반 선언적 캐싱

### 7. 공통 응답/에러 처리 (Common Response/Error Handling)
- **통일된 응답 구조**: `ApiResponse` 래퍼로 성공/실패 응답 구조 통일
- **글로벌 예외 처리**: `GlobalExceptionHandler`로 예외 상황 일관성 있게 처리
- **에러 로깅**: 보안/에러 로그를 파일로 기록하여 운영 관찰 가능

### 8. 배치·스케줄러 (5종, 주기 설정 가능)
- **QueueProcessingScheduler**: 대기열 상위 N명 입장 허용. 기본 2초 주기.
- **QueueCleanupScheduler**: 대기열 만료 토큰 제거. 기본 60초 주기.
- **HoldCleanupScheduler**: 만료된 좌석 홀드 정리 + Kafka HOLD_EXPIRED 발행. 기본 60초 주기.
- **RefundForCancelledConcertScheduler**: CANCELLED 공연의 COMPLETED 결제 청크 환불. 기본 5분 주기, 배치 50건.
- **KafkaOutboxPublishScheduler**: DB outbox의 PENDING 행을 Kafka로 발행 (`RESERVATION_CONFIRMED` 등). 기본 500ms 주기.

주기는 모두 `application.properties`/환경 변수로 변경 가능. 주기 선택 근거·튜닝은 [docs/infra.md](docs/infra.md)의 "스케줄러 주기 가이드" 참고.

## 🔧 문제 해결 포인트

### 1. 대규모 트래픽 처리
**문제**: 콘서트 오픈 시 동시에 수천 명이 접속하여 서버 부하 발생

**해결**:
- 콘서트별 대기열로 트래픽 분산
- Redis ZSet으로 O(log N) 순번 조회로 성능 최적화
- 배치 처리로 서버 부하 분산 (기본 2초마다 상위 50명 입장 허용)
- 폴링 주기 최적화 (2초)로 사용자 경험과 서버 부하 균형

**성능**:
- 대기열 진입: O(log N)
- 순번 조회: O(log N)
- 대기인원 수: O(1)

### 2. 중복 예약 방지
**문제**: 동시에 같은 좌석을 선택할 경우 중복 예약 발생 가능

**해결**:
- 좌석 단위 분산 락 (`lock:seat:{seatId}`)으로 동시성 제어
- Redis TTL 기반 홀드 시스템으로 일정 시간 후 자동 해제
- 홀드 검증 후 결제 완료 시에만 예약 확정되도록 경쟁 상황 제어
- Lua 스크립트로 락 해제 시 토큰 일치 검증

**플로우**:
```
좌석 선택 → 분산 락 획득 → 홀드 생성 → 결제 완료 → 예약 확정(자동) → 락 해제
```

### 3. 만료 자동화
**문제**: 홀드 만료 시 수동으로 처리해야 함

**해결**:
- Redis ZSet (`hold:expires`)에 만료 시각 기준 정렬
- 스케줄러가 주기적으로 만료된 홀드 스캔 (기본 60초마다)
- 만료 시 Kafka로 `HOLD_EXPIRED` 이벤트 발행
- Kafka Consumer가 이벤트 수신 후 알림 저장 및 SSE 전송

### 4. 실시간 알림 전달
**문제**: 폴링 방식은 지연이 발생하고 서버 부하 증가

**해결**:
- SSE를 통한 서버 푸시 방식으로 즉시 알림 전달
- Kafka 이벤트와 연동하여 이벤트 발생 시 즉시 클라이언트에 전달
- 폴링을 백업용으로 유지하여 SSE 연결 실패 시에도 동작 보장
- 사용자별 SSE 연결 관리로 확장성 확보

### 5. 관측성 (Observability)
**문제**: 운영 중 문제 발생 시 원인 파악이 어려움

**해결**:
- 지표 API (`GET /api/metrics`)로 실시간 접속자 수, 콘서트 수 등 제공
- **Prometheus 커스텀 메트릭**: 대기열(`ticketing_queue_waiting_count`), 홀드 생성/활성/해제(`ticketing_hold_created_total`, `ticketing_holds_active_count`, `ticketing_hold_released_total`), 전환율·결제(`ticketing_reservation_confirmed_total`, `ticketing_payment_completed_total`), 환불 배치(`ticketing_refund_processed_total`), 락 실패(`ticketing_lock_acquire_failures_total`) 등으로 관측 ([docs/monitoring.md](docs/monitoring.md))
- 보안/에러 로그를 파일로 기록 (`logs/ticketing.log`)
- Redis Insight, Kafka UI로 인프라 상태 모니터링 가능

### 6. 확장성 (Scalability)
**문제**: 단일 서버로는 대규모 트래픽 처리 불가

**해결**:
- 세션/홀드/알림/대기열을 Redis로 외부화하여 수평 확장 대비
- Spring Session으로 세션 공유
- Redis 연결 풀링으로 연결 관리 최적화
- Kafka로 이벤트 기반 비동기 처리

### 7. 부하 테스트·수용 인원 (Knee point)
- **k6 스크립트**: [load-tests/](load-tests/) 에 API 건강·대기열·좌석/홀드·DB 읽기·캐시 핫리드(5축) 및 E2E `full-flow.js` 제공 ([load-tests/README.md](load-tests/README.md))
- **목표 인프라**: t3a.medium 1대(Redis/Kafka/Prometheus/Grafana) + t3.small 2대(앱) 구성 시 동시 사용자 수·RPS를 단계적으로 올려 **knee point** 측정. 결과는 “동시 N명(또는 RPS)까지 검증”으로 문서화 ([docs/deployment-ec2.md](docs/deployment-ec2.md), [docs/load-test-results.md](docs/load-test-results.md)). 동시성·대기열·홀드 단위/통합 테스트, Redis·Kafka·DB 헬스, 비즈니스 메트릭·락 재시도 설정으로 검증·운영 보강.

## 📋 핵심 기능

### 인증 및 사용자 관리
- 회원가입/로그인 (Spring Security 기반)
- 마이페이지 (예매 내역 조회)
- 세션 관리 (Redis 기반)

### 콘서트 관리
- 콘서트 목록 조회 (Redis 캐싱)
- 카테고리별 필터링
- 검색 기능
- 콘서트별 좌석 현황 조회

### 대기열 시스템
- 콘서트별 대기열 진입 (토큰 발급)
- 순번 폴링 (2초마다)
- 입장 허용 감지 및 자동 리다이렉트
- 대기열 나가기

### 좌석 예매
- 좌석 선택 및 홀드 생성 (10분 TTL, 설정 가능)
- 홀드 만료 알림 (SSE 실시간 전달)
- 결제 완료 시 예약 확정 (별도 예약 확정 API 없음)
- 예약 내역 조회

### 알림 시스템
- 실시간 알림 수신 (SSE)
- 알림 목록 조회
- 알림 삭제

### 지표 대시보드
- 실시간 접속자 수
- 콘서트 수
- 예약 통계

## 🛠 기술 스택

### Backend
- **Spring Boot 3.4.1**: 애플리케이션 프레임워크
- **Spring Security**: 인증 및 보안
- **Spring OAuth2 Client**: Google 로그인(Authorization Code, 서버 콜백 후 Redis 세션)
- **Spring Data JPA**: 데이터베이스 접근
- **Spring Data Redis**: Redis 접근
- **Spring Kafka**: Kafka 통합
- **Spring Session**: 세션 관리
- **Java 21**: 프로그래밍 언어

### Database & Storage
- **MySQL**: 영구 데이터 저장 (콘서트, 좌석, 예약)
- **Redis**: 세션/홀드/락/캐시/대기열 저장
  - Lettuce 클라이언트 (비동기)
  - 연결 풀링 (commons-pool2)

### Message Queue
- **Apache Kafka**: 이벤트 스트리밍
  - 토픽: `ticketing.seat-hold-events`
  - Consumer Group: `ticketing-notification`

### Frontend
- **Vanilla JavaScript**: 클라이언트 사이드 로직
- **HTML/CSS**: 정적 페이지
- **EventSource API**: SSE 클라이언트

### Infrastructure
- **Docker Compose**: 로컬 개발 환경
- **Gradle**: 빌드 도구

## 🚀 로컬 실행

### 사전 요구사항
- Java 21+
- Docker & Docker Compose
- MySQL 8.0+

### 1. 환경 변수 설정
`.env` 파일 생성:
```env
DB_URL=jdbc:mysql://localhost:3306/ticketing?useSSL=false&serverTimezone=Asia/Seoul
DB_USERNAME=root
DB_PASSWORD=your_password

REDIS_HOST=localhost
REDIS_PORT=6379

KAFKA_BOOTSTRAP_SERVERS=localhost:29092
KAFKA_CONSUMER_GROUP=ticketing-notification

# 이메일/SMS 알림 사용 시 (선택)
# MAIL_USERNAME=your@gmail.com
# MAIL_PASSWORD=app_password
# SOLAPI_API_KEY=...
# SOLAPI_API_SECRET=...
# SOLAPI_FROM_NUMBER=01000000000

# Google OAuth2 (선택, 로그인 화면의「Google로 로그인」)
# GOOGLE_CLIENT_ID=....apps.googleusercontent.com
# GOOGLE_CLIENT_SECRET=...
# Google Cloud Console > OAuth 클라이언트 > 승인된 리디렉션 URI:
#   http://localhost:8080/login/oauth2/code/google
```

최초 Google 로그인 시 DB에 사용자가 자동 생성(JIT)되며, 내부 `username`은 `g{Google sub}` 형태(충돌 시 접미사)입니다. OAuth 계정은 알림 기본값이 이메일이며 전화번호가 없을 수 있습니다.

### 2. 인프라 실행
```bash
# Kafka, Redis, Zookeeper 실행
docker compose up -d

# MySQL 실행 (별도 설치 필요)
# 또는 docker-compose.yml에 MySQL 서비스 추가
```

### 3. 애플리케이션 실행
```bash
# 빌드
./gradlew clean build

# 실행
./gradlew bootRun
```

### 4. 접속
- 웹 애플리케이션: http://localhost:8080
- Kafka UI: http://localhost:8081
- Redis Insight: http://localhost:5540

## 📖 데모 시나리오

1. **회원가입/로그인**
   - `/signup.html`에서 회원가입
   - `/login.html`에서 ID/비밀번호 로그인 또는 Google OAuth(환경 변수 설정 시)

2. **콘서트 탐색**
   - `/app.html`에서 카테고리/검색으로 콘서트 탐색
   - 콘서트 목록은 Redis 캐시로 빠르게 로드

3. **대기열 진입**
   - 콘서트 선택 시 `/queue.html?concertId={id}`로 대기열 진입
   - 토큰 발급 및 순번 표시

4. **순번 대기**
   - 2초마다 폴링으로 순번 업데이트
   - 예상 대기 시간 표시

5. **입장 허용**
   - 스케줄러가 상위 N명 입장 허용
   - 입장 허용 시 `/concert.html?concertId={id}&queueToken={token}`로 자동 이동

6. **좌석 선택**
   - 좌석 선택 후 홀드 생성 (10분 TTL, 설정 가능)
   - 홀드 생성 시 Kafka로 `HOLD_CREATED` 이벤트 발행

7. **결제 완료 및 예약 확정**
   - 결제 완료 API 호출 시 예약 확정 (DB 기록). `RESERVATION_CONFIRMED` 는 DB transactional outbox에 같은 트랜잭션으로 적재되고, 스케줄러가 Kafka로 발행한다. DB 커밋 후 리스너가 Redis 홀드를 제거한다.

8. **홀드 만료 알림**
   - 스케줄러가 만료된 홀드 스캔
   - Kafka로 `HOLD_EXPIRED` 이벤트 발행
   - SSE를 통해 실시간 알림 수신

9. **예매 내역 조회**
   - `/reservations.html`에서 예매 내역 조회
   - `/mypage.html`에서 사용자 정보 및 예매 내역 확인

## 📊 성능/캐시 전략 요약

### 대기열 시스템
- **Redis ZSet**: O(log N) 순번 조회로 성능 최적화
- **배치 처리**: 2초마다 상위 50명 처리로 서버 부하 분산
- **토큰 TTL**: `ticketing.queue.token-ttl-seconds`로 설정 (기본 30분)

### 서버 캐시
- **콘서트 목록**: Redis 캐시로 응답 속도 향상 및 DB 부하 최적화
- **캐시 TTL**: 5분으로 데이터 신선도 유지

### 클라이언트 캐시
- **카테고리 전환**: 30초 TTL 메모리 캐시로 즉시 반응
- **폴링 최적화**: 대기열 2초, 알림 30초로 서버 부하 최소화

### 세션 외부화
- **Redis 세션**: 다중 인스턴스 확장 대응
- **세션 TTL**: 30분으로 자동 만료
- **대기열 토큰 TTL**: `ticketing.queue.token-ttl-seconds` (기본 30분, 설정 가능)

### 실시간 알림
- **SSE**: 즉시 전달로 사용자 경험 향상
- **폴링 백업**: SSE 연결 실패 시 대비

### 배치/스케줄러
- **대기열 입장**: 2초마다 상위 50명 입장 허용
- **홀드 만료**: 60초마다 만료 홀드 스캔·정리 및 Kafka 이벤트 발행
- **취소 공연 환불**: 5분마다 CANCELLED 공연의 완료 결제 청크 환불 (포인트 복원, 예약/좌석 해제)

## 📁 프로젝트 구조

```
ticketing/
├── src/main/java/com/inyoung/ticketing/
│   ├── auth/              # 인증 및 사용자 관리
│   ├── concert/           # 콘서트 도메인
│   ├── seat/              # 좌석 도메인
│   ├── hold/              # 홀드 도메인 (Redis 기반)
│   ├── reservation/       # 예약 도메인
│   ├── queue/             # 대기열 시스템
│   ├── notification/      # 알림 시스템 (SSE 포함)
│   ├── metrics/           # 지표 수집
│   ├── scheduler/         # 스케줄러 (홀드 정리, 대기열 처리, 취소 공연 환불 배치)
│   ├── lock/              # 분산 락
│   ├── config/            # 설정 클래스
│   └── common/            # 공통 유틸리티
├── src/main/resources/
│   ├── static/            # 정적 리소스 (HTML, JS, CSS)
│   └── application.properties
├── docs/                  # 문서 (포트폴리오/면접관용) — 목차: docs/README.md
│   ├── README.md         # 문서 목차
│   ├── architecture.md   # 아키텍처 및 플로우
│   ├── api.md            # API 문서
│   └── ...
├── my-docs/              # 상세/공부용 (워크플로우, 소스 구조, Redis/Kafka 등)
│   ├── README.md         # my-docs 목차
│   ├── 01-full-workflow.md
│   └── ...
└── load-tests/           # 부하 테스트 스크립트
```

## 📚 문서

- **포트폴리오/면접관용**: [docs/](docs/README.md) — 아키텍처, API, 인프라, 동시성 등 요약. 목차는 [docs/README.md](docs/README.md) 참고.
- **상세/공부용**: [my-docs/](my-docs/README.md) — 전체 워크플로우, 소스 구조, 홀드·결제·스케줄러·Redis/Kafka 정리. 코드와 흐름 이해용.

요약 링크:
- [아키텍처/플로우](docs/architecture.md) | [API](docs/api.md) | [인프라](docs/infra.md) | [데이터 구조](docs/data.md) | [동시성](docs/concurrency.md) | [관리자](docs/admin-setup.md) | [EC2 배포](docs/deployment-ec2.md) | [부하 테스트](docs/load-test-results.md) | [모니터링](docs/monitoring.md)

## 🎓 학습 포인트

이 프로젝트를 통해 학습한 내용:

1. **대규모 트래픽 처리**: 대기열 시스템으로 트래픽 분산 및 공정한 순번 관리
2. **동시성 제어**: 분산 락과 Redis TTL로 경쟁 상태 해결
3. **이벤트 기반 아키텍처**: Kafka를 활용한 비동기 이벤트 처리
4. **실시간 통신**: SSE를 활용한 서버 푸시 구현
5. **캐싱 전략**: 서버/클라이언트 캐시 하이브리드 전략
6. **세션 관리**: Redis 기반 세션 외부화로 확장성 확보
7. **성능 최적화**: Redis ZSet으로 O(log N) 연산 활용
8. **운영 관찰성**: 지표 API 및 로깅으로 운영 관찰 가능

## 📝 라이선스

이 프로젝트는 개인 포트폴리오용으로 제작되었습니다.
