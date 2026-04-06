package com.inyoung.ticketing.auth.jwt.repository;

import java.util.Optional;
import com.inyoung.ticketing.auth.jwt.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link RefreshToken} 엔티티. jti 유니크로 단건 조회·폐기에 사용한다.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
	Optional<RefreshToken> findByJti(String jti);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.familyId = :familyId")
	int revokeAllByFamilyId(@Param("familyId") String familyId);
}
