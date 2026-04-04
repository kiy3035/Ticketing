# ADR-004: 결제 API 멱등성 키 도입

## 상태
승인됨 (Accepted)

## 컨텍스트
- 네트워크 불안정 시 클라이언트가 결제 요청을 재전송할 수 있다
- 동일 요청이 2번 처리되면 이중 결제 / 이중 포인트 차감이 발생한다
- 토스페이먼츠 같은 PG사도 멱등성 키 패턴을 권장한다

## 결정
**`Idempotency-Key` 요청 헤더 + Redis TTL 저장** 패턴을 도입한다.

### 동작 흐름
```
Client → POST /api/payments/request (Header: Idempotency-Key: abc123)
  ├─ Redis에 "idempotency:abc123" 없음 → PROCESSING 마커 저장 → 로직 실행 → 결과 저장
  ├─ Redis에 결과 있음 → 캐시된 결과 즉시 반환
  └─ Redis에 PROCESSING 마커 있음 → 409 Conflict (다른 요청이 처리 중)
```

### AOP 기반 구현
```java
@Idempotent(ttlSeconds = 86400)  // 24시간 유지
@PostMapping("/request")
public PaymentResponse request(...) { ... }
```

## 대안 검토

| 방식 | 장점 | 단점 | 판정 |
|------|------|------|------|
| DB unique 제약 | 영속, 간단 | 추가 테이블 필요, 느림 | ⚠️ |
| **Redis SETNX + TTL** | 빠름, 자동 만료 | Redis 장애 시 미작동 | ✅ 채택 |
| 클라이언트 제어 | 서버 부하 없음 | 신뢰 불가 | ❌ |

## 결과
- 네트워크 재시도 시 이중 결제 원천 차단
- AOP 어노테이션으로 적용이 간편 (비침투적)
- Redis 장애 시 키가 없으면 멱등성 체크 없이 통과 (가용성 우선)
