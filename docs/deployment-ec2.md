# 인프라 구성 & 스케일아웃

## 운영 구성

```
[사용자] → [nginx (인프라 서버)] → [t3a.small 앱서버 #1 / #2]
                                          ↓
                       [t3a.medium: Redis, Kafka, Prometheus, Grafana, nginx]
                                          ↓
                                     [MySQL (RDS)]
```

| 서버 | 스펙 | 역할 |
|------|------|------|
| 인프라 서버 | t3a.medium | Redis 7, Kafka, Prometheus, Grafana, nginx |
| 앱 서버 #1 | t3a.small | Spring Boot 3.4 / Java 21 |
| 앱 서버 #2 | t3a.small | Spring Boot 3.4 / Java 21 |
| k6 서버 | t3a.small | 부하 테스트 전용 |

## 스케일아웃 체크리스트 (1대 → 2대 + nginx)

앱은 상태를 Redis·MySQL·Kafka에 두므로 동일 JAR를 여러 대에 배포하고 nginx로 분산 가능. **현재 운영은 2대 + nginx 구성으로 부하 테스트 완료 (Phase 4·5·6·7·8).**

| 항목 | 내용 |
|------|------|
| **세션/락/대기열** | 모두 Redis 기반 → 앱 N대가 동일 Redis 공유, 상태 자동 공유 |
| **JWT** | 운영 인스턴스에 동일 `JWT_SECRET` 주입. Access 블랙리스트는 Redis 공유로 모든 인스턴스에서 차단 |
| **배치 스케줄러** | Redis 분산 락으로 단일 인스턴스만 실행 보장 |
| **Kafka consumer** | `group-id` 동일 → 파티션 수 ≥ 소비자 수 필요 |
| **SSE** | 사용자가 어느 인스턴스에 연결되는지 고정 안 됨 → 필요 시 nginx Sticky Session(`ip_hash` 등) 검토 |
| **로드밸런싱** | nginx `least_conn` + passive health check (`max_fails=2 fail_timeout=10s`) + `proxy_next_upstream` |
| **헬스체크** | `GET /actuator/health` (ticketingDatastores UP 기준 — Redis PING + DB `isValid`) |
| **Graceful Shutdown** | `application-prod.properties`: `server.shutdown=graceful`, timeout 30s |
| **정적 리소스** | prod 프로필에서 `file:` 경로 제거 (`spring.web.resources.static-locations=classpath:/static/`) |
