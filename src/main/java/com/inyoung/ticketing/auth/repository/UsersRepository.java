package com.inyoung.ticketing.auth.repository;

import java.util.Optional;
import com.inyoung.ticketing.auth.domain.Users;
import org.springframework.data.jpa.repository.JpaRepository;

// 사용자 계정 리포지토리
public interface UsersRepository extends JpaRepository<Users, Long> {
	// 사용자 아이디로 조회
	Optional<Users> findByUsername(String username);

	// 사용자 아이디 중복 여부 확인
	boolean existsByUsername(String username);
}
