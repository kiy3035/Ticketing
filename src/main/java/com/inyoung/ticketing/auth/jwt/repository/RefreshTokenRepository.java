package com.inyoung.ticketing.auth.jwt.repository;

import java.util.Optional;
import com.inyoung.ticketing.auth.jwt.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link RefreshToken} 엔티티. jti 유니크로 단건 조회·폐기에 사용한다.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
	Optional<RefreshToken> findByJti(String jti);
}
