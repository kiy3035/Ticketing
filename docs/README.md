# 포트폴리오 문서

> 대용량 트래픽 처리 · 좌석 동시 선점 제어 · 분산 인프라 운영 경험을 정리한 백엔드 포트폴리오 문서입니다.

## 문서 목차

| 문서 | 내용 |
|------|------|
| [포트폴리오 메인](backend-portfolio) | 아키텍처, 핵심 구현, 부하 테스트 결과, ADR 통합 |
| [기술 결정 (ADR)](decisions) | 기술 결정 5가지 (락, Kafka, DB락, 멱등, Virtual Thread) |
| [시퀀스 다이어그램](sequence-diagrams) | 홀드·결제·Saga 보상 핵심 시퀀스 |
| [JWT 인증 설계](jwt-auth) | JWT 4-case 재발급, 탈취 감지, family 폐기 |
| [데이터 참조표](data) | Redis 키 구조, Kafka 토픽/이벤트 참조표 |
| [인프라 설정](infra) | 스케줄러 5종, Outbox 설정, 주요 설정값 |
| [배포 구성](deployment-ec2) | 인프라 구성, 스케일아웃 체크리스트 |
| [모니터링](monitoring) | Prometheus 커스텀 메트릭, Golden Signals PromQL |
| [부하 테스트](load-test-portfolio) | 부하 테스트 상세 결과 (k6 × Grafana) |
