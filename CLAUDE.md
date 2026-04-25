# 프로젝트 개요
실무 수준의 백엔드 포트폴리오 프로젝트. 
대용량 트래픽 처리, 좌석 동시 선점 제어, 인프라 운영 경험을 포트폴리오로 증명하는 것이 핵심

# 핵심 기술 과제
대용량 트래픽 처리 — 동시 접속·요청 처리, knee point / bottleneck 탐지
좌석 동시 선점 제어 — 여러 사용자가 같은 좌석을 중복 선점하지 않도록 분산 락(Redis)으로 동시성 제어
스케일아웃 대응 — 현재 t3a.small 1대 → 추후 ALB + t3.small 2대로 확장 예정

# 인프라 구성
인프라서버 : t3a.medium (Redis, Kafka, Prometheus, Grafana), docker에 들어있음
앱 서버 : t3a.small Java 애플리케이션 (추후 2대 예정, ALB도 예정), docker에 들어있음
k6 서버 : t3a.small

현재는 앱 서버 1대로 운영 중. ALB 도입 후 2대로 스케일아웃 예정.

## 기술 스택
| 구분 | 기술 | 용도 |
|------|------|------|
| Language | Java 21 | LTS, Virtual Thread |
| Framework | Spring Boot 3.4.1 | Web, Security, Data JPA, Kafka |
| Database | MySQL 8.0 | 콘서트/좌석/예약/결제 영속 데이터 |
| Cache/Lock | Redis 7 | JWT Access 블랙리스트, 분산 락, 좌석 홀드, 대기열, 캐시, 알림 |
| Message Queue | Kafka | 이벤트 드리븐: 홀드 이벤트, 결제 완료 알림 |
| Migration | Flyway | DB 스키마 버전 관리 |
| Monitoring | Prometheus + Grafana | 비즈니스/인프라 메트릭, 대시보드 |
| Load Test | k6 | 부하 테스트, Knee Point 측정 |
| Resilience | Resilience4j | Redis 서킷브레이커 |


## 코드 규칙
1. 주석은 한국어로 작성
2. 로그는 Slf4j 사용, 운영 가시성이 필요한 지점(락 획득/해제, 대기열 진입, 예매 완료)은 반드시 로그 남길 것
3. 매직 넘버·임계치는 절대 하드코딩 금지 → application.properties 또는 환경변수로 외부화
4. @Transactional 범위는 최소화. 락 획득 → 트랜잭션 시작 순서를 지킬 것

## 참고사항
1. 새로운 기능 구현 전에 설계 방향을 먼저 논의하고 코드 작성
2. 임계치·설정값이 등장하면 반드시 application.properties 외부화 포함해서 작성
3. 성능에 민감한 코드(락, 쿼리)는 이유 설명 주석 포함
4. 스케일아웃(앱 서버 2대) 환경을 항상 염두에 두고 설계 (세션 공유, 락 분산 등)
5. 부하 테스트 시나리오·결과 해석 요청 시 knee point / bottleneck 관점으로 분석