# EC2 배포 가이드 (포트폴리오 목표 구성)

실무에 가까운 백엔드 포트폴리오용 목표 인프라 구성이다.

## 목표 스펙

| 구분 | 인스턴스 | 용도 |
|------|----------|------|
| 인프라 1대 | **t3a.medium** | Redis, Kafka, Prometheus, Grafana (필요 시 MySQL 포함 또는 RDS 분리) |
| 앱 서버 2대 | **t3.small** | Java 애플리케이션 (무상태, 세션·대기열·락은 Redis 공유) |

앱 2대 앞단에 **ALB(Application Load Balancer)** 를 두고, 트래픽을 분산한다.

## 구성 요약

```
[사용자] → [ALB] → [t3.small #1] ─┐
                → [t3.small #2] ─┼→ [t3a.medium: Redis, Kafka, Prometheus, Grafana]
                                 │  (및 MySQL 또는 RDS)
```

- **세션**: Redis에 저장하므로 앱 2대가 동일 Redis를 바라보면 세션 공유됨.
- **대기열·락**: Redis 기반이므로 인프라 서버의 Redis 한 대를 앱 2대가 공유.
- **Kafka**: 알림 등 이벤트용. 인프라 서버에 1노드 구성 가능.

## t3a.medium (인프라 서버)

1. Redis, Kafka(Zookeeper), Prometheus, Grafana를 Docker Compose 또는 수동 설치로 구성.
2. `application.properties`/환경 변수에서 앱이 접속할 주소를 이 서버의 private IP(또는 DNS)로 설정.
3. Prometheus `prometheus.yml`에서 앱 타겟을 t3.small 2대의 private IP:8080 또는 ALB 뒤 경로로 설정할 수 있음 (또는 각 인스턴스 직접 스크래핑).

## t3.small x2 (앱 서버)

1. Java 21 + 애플리케이션 jar 배포. 동일 jar를 2대에 배포.
2. 환경 변수: `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS` 등은 인프라 서버(t3a.medium) 주소로 설정.
3. 헬스체크: `/actuator/health` 사용. ALB 타겟 그룹 헬스체크 경로를 해당 경로로 설정.

## ALB

- 리스너: HTTP 80 또는 HTTPS 443 → 타겟 그룹(두 t3.small 인스턴스, 포트 8080).
- Sticky Session: 선택 사항. Redis 세션을 쓰면 필수는 아님.

## 부하 테스트·knee point

- 실제 위 구성으로 올린 뒤, `load-tests/` 의 k6 스크립트로 부하를 걸고, Prometheus/Grafana로 지표를 보면서 **몇 명(또는 RPS)까지 안정적인지** knee point를 기록한다.
- 문서화: `README` 또는 별도 문서에 “동시 N명(또는 RPS)까지 검증” 수치를 명시하면 포트폴리오에 유리하다.
