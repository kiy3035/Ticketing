package com.inyoung.ticketing.auth.repository;

import java.util.Optional;
import com.inyoung.ticketing.auth.domain.Users;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * ════════════════════════════════════════════════════════════════
 * [UsersRepository]
 * ════════════════════════════════════════════════════════════════
 */
public interface UsersRepository extends JpaRepository<Users, Long> {

	// 사용자 아이디로 조회 (락 없음 — 단순 조회용)
	Optional<Users> findByUsername(String username);

	/**
	 * 사용자 아이디로 조회 + 배타 락 (포인트 차감 전용).
	 *
	 * ■ 왜 포인트 차감에 @Lock이 필요한가?
	 *   포인트는 "잔액" 개념이라 동시성 제어가 필수다.
	 *
	 *   시나리오 (락 없음):
	 *   사용자가 포인트 5000원 보유 상태에서
	 *   트랜잭션 A: 5000원 결제 → 잔액 0 읽음
	 *   트랜잭션 B: 5000원 결제 → 잔액 0 읽음 (A가 아직 커밋 안 함)
	 *   A: 5000 - 5000 = 0 으로 저장 (커밋)
	 *   B: 5000 - 5000 = 0 으로 저장 (커밋) → 잔액이 -5000인데 0으로 저장됨
	 *   → 포인트가 중복 차감되는 버그 발생.
	 *
	 *   시나리오 (락 있음):
	 *   트랜잭션 A: 락 획득 후 5000원 차감 → 0 저장 후 커밋 → 락 해제.
	 *   트랜잭션 B: A가 커밋하기 전까지 대기 → A 커밋 후 잔액 0 읽음 → 잔액 부족 예외 발생.
	 *   → 중복 차감 방지.
	 *
	 * ■ 이 락과 Redis 분산 락의 역할 분리
	 *   Redis 분산 락: 좌석 선점 경쟁 (특정 좌석을 누가 먼저 잡느냐)
	 *   DB 비관적 락: 포인트 잔액 일관성 (같은 사람의 포인트 동시 차감 방지)
	 *   두 락이 서로 다른 문제를 해결하기 때문에 함께 존재한다.
	 *
	 * ■ 단점
	 *   - 같은 사용자가 동시에 여러 결제를 시도하면 직렬화(순차 처리)됨 → 응답 지연.
	 *   - 데드락 방지를 위해 락 획득 타임아웃 설정 권장.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Users> findWithLockByUsername(String username);

	// 사용자 아이디 중복 여부 확인 (회원가입 시 사용)
	boolean existsByUsername(String username);

	/**
	 * 사용자명 또는 이메일로 검색 (어드민 페이지용).
	 * ContainsIgnoreCase: LIKE '%?%' + 대소문자 구분 없음.
	 * 인덱스를 타지 않아 사용자 수가 많아지면 느려질 수 있음.
	 * 어드민 전용이라 트래픽이 낮아 현재는 허용.
	 */
	Page<Users> findByUsernameContainsIgnoreCaseOrEmailContainsIgnoreCase(
		String username,
		String email,
		Pageable pageable
	);
}
