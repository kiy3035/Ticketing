# ADR-003: 비관적 락 vs 낙관적 락 선택

## 상태
승인됨 (Accepted)

## 컨텍스트
결제, 포인트 차감, 예약 확정 등 금전/좌석 상태 변경에 DB 수준의 동시성 제어가 필요하다.

## 결정
**결제/포인트**: DB 비관적 락 (`PESSIMISTIC_WRITE`, `SELECT ... FOR UPDATE`)
**좌석 홀드**: Redis 분산 락 (ADR-001 참조)

### 비관적 락 적용 지점

```java
// PaymentRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Payment> findWithLockByPaymentKey(String paymentKey);

// UsersRepository  
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Users> findWithLockByUsername(String username);

// ReservationRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Reservation> findWithLockById(Long id);
```

## 비관적 vs 낙관적 비교

| 기준 | 비관적 락 | 낙관적 락 (@Version) |
|------|-----------|---------------------|
| 충돌 빈도 | **높을 때** 유리 | 낮을 때 유리 |
| 성능 | 락 대기 발생 | 재시도 비용 |
| 데드락 | 가능 (타임아웃 설정 필요) | 없음 |
| 적용 난이도 | 낮음 (애노테이션) | 재시도 로직 필요 |

## 선택 근거
1. **결제 충돌 빈도가 높다**: 같은 좌석에 대한 결제가 동시에 들어올 수 있음
2. **포인트 차감은 실패 허용 불가**: 낙관적 락 재시도 중 다른 트랜잭션이 포인트를 소진하면 최종 실패
3. **구현 단순성**: `@Lock` 어노테이션만으로 적용 가능
4. **데드락 방지**: 항상 Payment → Users 순서로 락을 획득하는 규칙 적용

## 결과
- 결제/포인트 정합성 100% 보장
- 동시 결제 테스트에서 중복 차감 0건 확인
