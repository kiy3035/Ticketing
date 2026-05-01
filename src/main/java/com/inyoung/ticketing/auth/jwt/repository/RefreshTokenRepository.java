package com.inyoung.ticketing.auth.jwt.repository;

import java.util.Optional;
import com.inyoung.ticketing.auth.jwt.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ════════════════════════════════════════════════════════════════
 * [RefreshTokenRepository]
 *
 * ■ findByJti
 *   JWT 재발급 요청이 오면 토큰에서 jti 클레임을 꺼내 DB 조회.
 *   → revoked = true면 폐기된 토큰이므로 인증 거부.
 *   → expiresAt < now 이면 만료된 토큰이므로 인증 거부.
 *   → 정상이면 새 Access 토큰 + 새 Refresh 토큰 발급, 기존 jti revoked = true 처리.
 *
 *   jti 컬럼에 unique 제약이 있으므로 이 조회는 항상 0건 또는 1건.
 *   인덱스도 자동 생성되어 조회 속도가 빠르다.
 * ════════════════════════════════════════════════════════════════
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
	Optional<RefreshToken> findByJti(String jti);
}
