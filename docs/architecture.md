# 아키텍처 (기술 스택 · 폴더 구조 · 인프라)

> 이 문서는 `CLAUDE.md`에서 분리된 상세 참조다. 매 세션 자동 적재(토큰 소모)를 피하기 위해
> `CLAUDE.md`에는 이 파일의 **경로만** 남기고(`@` import 아님), 아키텍처 작업이 필요할 때만 읽는다.

## 기술 스택
| 구분 | 기술 | 용도 |
|------|------|------|
| Language | Java 21 | LTS, Virtual Thread |
| Framework | Spring Boot 3.4.1 | Web, Security, Data JPA, Kafka |
| Database | MySQL 8.0 | 콘서트/좌석/예약/결제 영속 데이터 |
| Cache/Lock | Redis 7 | JWT 블랙리스트, 분산 락, 좌석 홀드, 대기열, 캐시, 알림 |
| Message Queue | Kafka | 이벤트 드리븐: 홀드 이벤트, 결제 완료 알림 |
| Migration | Flyway | DB 스키마 버전 관리 (`src/main/resources/db/migration/V*.sql`) |
| Monitoring | Prometheus + Grafana | 비즈니스/인프라 메트릭 |
| Load Test | k6 | 부하 테스트, knee point 측정 |
| Resilience | Resilience4j | Redis 서킷브레이커 |

## 폴더 구조
도메인 패키지 19개 (`src/main/java/com/inyoung/ticketing/`, 알파벳순 — 누락 없이 전부):
```
ticketing/
├── admin/         # 관리자 기능
├── auth/          # 인증·JWT (Access/Refresh/블랙리스트)
├── cache/         # Redis 캐시 (@Cacheable 등)
├── common/        # 공통 (ApiResponse, GlobalExceptionHandler 등)
├── concert/       # 콘서트 도메인
├── config/        # 설정 클래스
├── debug/         # 디버그/개발 보조
├── health/        # 헬스 체크
├── hold/          # 좌석 홀드 (Redis TTL, hold:expires)
├── lock/          # 분산 락 (lock:seat:{seatId}, Lua 해제)
├── metrics/       # Prometheus 커스텀 메트릭
├── notification/  # SSE 실시간 알림
├── outbox/        # transactional outbox (Kafka 발행 보장)
├── payment/       # Mock 포인트 결제
├── queue/         # 대기열 (Redis ZSet, 토큰, 순번)
├── reservation/   # 예약 확정
├── scheduler/     # 배치 5종 (대기열 입장, 홀드 정리, outbox 발행, 환불 등)
├── seat/          # 좌석 도메인
└── seller/        # 판매자 기능
```
레포 루트 주요 디렉토리 (`.gradle/.idea/.vscode/.cursor/.settings` 등 빌드·IDE 설정 제외):
```
├── src/               # 앱 소스 (resources: application*.properties, db/migration/V*.sql)
├── loadtest-harness/  # 부하 테스트 자동화 파이프라인(Python) + pytest + AI 분석
├── load-tests/        # k6 스크립트 (knee-point.js 등)
├── .github/           # CI/CD: 배포, 하네스 CI, AI PR 리뷰, 주간 보안 점검
├── .claude/skills/    # Claude Code 스킬 (commit / loadtest / loadtest-analyze / loadtest-compare)
├── docs/              # 포트폴리오/면접관용 문서 (AI 자동화 진입점: docs/ai-productivity.md)
├── my-docs/           # 상세/공부용 문서
├── interview/         # 면접 예상 Q&A 문서
├── test-code/         # 테스트 전략·산출물 문서
├── nginx/             # nginx 설정
└── portfolio/         # 부하 테스트 스크린샷 모음
```

## 인프라 (nginx 단독 구성 — ALB 미사용)
- 인프라 서버 `t3a.medium`: Redis, Kafka, Prometheus, Grafana, **nginx(LB·리버스 프록시 겸임)**
- 앱 서버 ×2 `t3a.small`: Java 앱 (스케일아웃)
- k6 서버 `t3a.small`: 부하 생성
- **스케일아웃(앱 2대)을 항상 전제로 설계** (세션 공유, 락 분산 등은 Redis로 외부화)

> 인프라 상세(배포·구성)는 `docs/infra.md`, `docs/deployment-ec2.md` 참고.
