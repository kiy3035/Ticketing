# 부하 테스트 결과

> ⚠️ 이 문서는 부하 테스트 실행 후 결과를 기록하는 템플릿입니다.
> k6 실행 후 아래 항목을 채워 넣으세요.

## 테스트 환경

| 항목 | 값 |
|------|-----|
| App Server | t3.small × 1대 (추후 2대 + ALB) |
| Infra Server | t3a.medium × 1대 (Redis, Kafka, Prometheus, Grafana) |
| k6 Server | t3a.small × 1대 |
| Java | 21, -Xmx2g -Xms1g |
| MySQL | 8.0 |
| Redis | 7 |

## 트러블슈팅: `/actuator/health` 병목

### 현상
`api-health.js` (VU 50) 부하 테스트에서 `/actuator/health`만 82% 실패, p95 = 60초 (타임아웃).
같은 테스트의 `/api/queue/required`는 95% 성공.

### 원인
`management.endpoint.health.show-details=always` 설정으로, health check가 **매 요청마다 DB·Redis·Kafka·Disk 전체를 확인**했다.
이 중 하나(Kafka health indicator)가 응답 지연 → 전체 health 응답이 60초 타임아웃.
VU 50이 동시에 health를 호출하면 DB 커넥션 풀(10개)도 포화되어 다른 요청까지 영향.

### 해결
```properties
# 변경 전
management.endpoint.health.show-details=always

# 변경 후: 일반 요청은 {"status":"UP"} 즉시 반환, 상세 진단은 인증된 관리자만
management.endpoint.health.show-details=when-authorized
```

### 교훈
모니터링 엔드포인트도 부하 테스트 대상이다. health check가 무거우면 부하 시 DB 커넥션을 잡아먹어 비즈니스 API까지 영향을 준다.

---

## 시나리오별 결과

### 1. 대기열 진입

| 지표 | 결과 |
|------|------|
| 동시 VU | (기록) |
| TPS | (기록) |
| p50 응답시간 | (기록) |
| p95 응답시간 | (기록) |
| p99 응답시간 | (기록) |
| 에러율 | (기록) |

### 2. 좌석 홀드

| 지표 | 결과 |
|------|------|
| 동시 VU | (기록) |
| TPS | (기록) |
| p50 응답시간 | (기록) |
| p95 응답시간 | (기록) |
| 선점 성공률 | (기록) |
| 락 실패율 | (기록) |

### 3. E2E 예매

| 지표 | 결과 |
|------|------|
| 동시 VU | (기록) |
| 전체 예매 소요시간 p95 | (기록) |
| 예매 성공률 | (기록) |
| 구간별 병목 | (기록) |

## Knee Point 분석

> VU 수를 점진적으로 늘리면서 p95 응답시간이 급격히 올라가는 지점

| VU 수 | TPS | p95 (ms) | 에러율 | 판정 |
|-------|-----|----------|--------|------|
| 50 | | | | |
| 100 | | | | |
| 150 | | | | |
| 200 | | | | |
| 250 | | | | |

**Knee Point**: VU ___명 부근에서 p95 응답시간이 급격히 상승

## 병목 분석

(테스트 후 작성)

## 개선 방안

(테스트 후 작성)
