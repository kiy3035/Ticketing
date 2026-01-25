# Ticketing

백엔드 중심의 콘서트 예매 시스템입니다. Redis(세션/홀드/락/캐시)와 Kafka(이벤트 스트리밍)를 활용해 **동시성·정합성·확장성**을 고려한 설계를 구현했습니다. 로그인 기반 프론트까지 포함한 MVP이며, 실무 시나리오에 맞춘 흐름을 제공합니다.

## 포트폴리오 핵심 요약
- **좌석 홀드/만료**: Redis TTL + 분산 락으로 경쟁 상태 해결
- **이벤트 스트리밍**: Kafka로 HOLD/RESERVATION 이벤트 분리
- **세션 외부화**: Spring Session + Redis
- **캐시 계층**: 서버 Redis 캐시 + 클라이언트 캐시 하이브리드
- **공통 응답/에러**: 성공/실패 응답 구조 통일

## 문제 해결 포인트
- **중복 예약 방지**: 좌석 단위 락 → 홀드 검증 → 예약 확정 순서로 경쟁 상황 제어
- **만료 자동화**: Redis ZSET + 스케줄러로 만료 처리 및 알림 이벤트 발행
- **관측성**: 지표 API + 캐시/보안 로그로 운영 관찰 가능
- **확장성**: 세션/홀드/알림을 Redis로 외부화해 수평 확장 대비

## 핵심 기능
- 로그인/회원가입 및 인증 기반 접근 제어
- 콘서트 목록/카테고리/검색
- 좌석 선택 → 홀드 → 예약 확정
- 홀드 만료 알림(이벤트 기반)
- 대기열 스텁, 지표 대시보드

## 기술 스택
- Spring Boot, Spring Security, Spring Data JPA
- MySQL, Redis, Kafka
- Static Frontend (Vanilla JS)

## 로컬 실행
```bash
./gradlew bootRun
```

## 데모 시나리오
1. 회원가입 → 로그인
2. `/app.html`에서 카테고리/검색으로 콘서트 탐색
3. `/concert.html?concertId=...`에서 좌석 선택
4. 예매하기 → `/payment.html` 이동
5. 결제하기 → 예약 확정 및 상태 갱신
6. 홀드 만료 시 알림 패널에서 만료 알림 확인

## 성능/캐시 전략 요약
- **서버 캐시**: 콘서트 목록은 Redis 캐시로 응답 속도 및 DB 부하 최적화
- **클라이언트 캐시**: 카테고리 전환은 30초 TTL 메모리 캐시로 즉시 반응
- **세션 외부화**: Redis 세션으로 다중 인스턴스 확장 대응

## 실행 화면 스냅샷
- `docs/assets/app-list.png` 콘서트 목록/필터
- `docs/assets/concert-seat.png` 좌석 선택/홀드
- `docs/assets/payment.png` 결제 요약

## 문서
- [아키텍처/플로우](docs/architecture.md)
- [API 및 응답 스키마](docs/api.md)
- [인프라/환경 설정](docs/infra.md)
- [Redis/Kafka/세션 구조](docs/data.md)
