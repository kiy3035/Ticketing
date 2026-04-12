# 프로젝트 문서 (포트폴리오용)

`docs/` 는 **이력서·면접관이 10~20분 안에 훑을 수 있는 분량**을 목표로 한다. 깊은 공부용 노트는 저장소 루트의 `my-docs/` 를 사용한다.

## 문서 목차

| 문서 | 설명 |
|------|------|
| [architecture.md](architecture.md) | 구성도, 스택, 동시성·이벤트·결제·JWT, 락 키 요약 |
| [jwt-auth.md](jwt-auth.md) | JWT Access/Refresh, 블랙리스트, 4가지 재발급, SSE 쿼리 파라미터 |
| [decisions.md](decisions.md) | 기술 선택 이유 5가지 (락, Kafka, DB락, 멱등, Virtual Thread) |
| [sequence-diagrams.md](sequence-diagrams.md) | 홀드·결제·보상·DLQ 시퀀스, **정합성·실패 표(§5)** |
| [api.md](api.md) | REST API 요약 |
| [data.md](data.md) | Redis 키, Kafka 토픽·이벤트 참조표 |
| [infra.md](infra.md) | Docker Compose, 스케줄러, 설정, 헬스·JVM 한 페이지 |
| [deployment-ec2.md](deployment-ec2.md) | 목표 EC2 구성, ALB, 수평 확장 체크리스트 |
| [load-test-portfolio.md](load-test-portfolio.md) | 부하·용량 검증 프레임 (목적, 범위, 런북, 6패널, knee 판정) |
| [load-test-results.md](load-test-results.md) | 당일 런 메모·수치 (짧은 기록) |
| [monitoring.md](monitoring.md) | Prometheus / Grafana |
| [admin-setup.md](admin-setup.md) | 관리자·판매자 역할 및 API |
