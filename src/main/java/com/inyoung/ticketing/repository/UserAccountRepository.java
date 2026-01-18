package com.inyoung.ticketing.repository;

import java.util.Optional;
import com.inyoung.ticketing.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

// 사용자 계정 리포지토리
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
	// 사용자 아이디로 조회
	Optional<UserAccount> findByUsername(String username);

	// 사용자 아이디 중복 여부 확인
	boolean existsByUsername(String username);
}
