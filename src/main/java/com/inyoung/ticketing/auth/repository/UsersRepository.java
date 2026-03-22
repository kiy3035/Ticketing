package com.inyoung.ticketing.auth.repository;

import java.util.Optional;
import com.inyoung.ticketing.auth.domain.Users;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * 사용자 계정 리포지토리
 * 
 * 사용자 데이터의 조회, 저장, 검색 기능을 제공합니다.
 */
public interface UsersRepository extends JpaRepository<Users, Long> {
	// 사용자 아이디로 조회
	Optional<Users> findByUsername(String username);

	Optional<Users> findByOauthProviderAndOauthSubject(String oauthProvider, String oauthSubject);

	// 사용자 아이디로 조회 (락 포함)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Users> findWithLockByUsername(String username);

	// 사용자 아이디 중복 여부 확인
	boolean existsByUsername(String username);

	/**
	 * 사용자명 또는 이메일로 검색
	 */
	Page<Users> findByUsernameContainsIgnoreCaseOrEmailContainsIgnoreCase(
		String username,
		String email,
		Pageable pageable
	);
}
