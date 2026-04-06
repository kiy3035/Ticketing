package com.inyoung.ticketing.auth.jwt;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import com.inyoung.ticketing.config.TicketingProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * JWT 생성·파싱·만료 판별을 담당한다.
 * <p>
 * 알고리즘은 HS256이며, 비밀키는 {@link TicketingProperties#getJwt()} 의 {@code secret}(UTF-8 기준 최소 32바이트)에서 만든다.
 * Access 와 Refresh 는 모두 같은 키로 서명하며, {@code typ} 클레임으로 구분한다.
 * </p>
 */
@Service
public class JwtTokenService {
	/** JWT 내부에서 토큰 종류를 구분하는 커스텀 클레임 키 */
	public static final String CLAIM_TYP = "typ";
	/** {@link #CLAIM_TYP} 값: Access 토큰 */
	public static final String TYP_ACCESS = "access";
	/** {@link #CLAIM_TYP} 값: Refresh 토큰 */
	public static final String TYP_REFRESH = "refresh";
	/** Access 토큰에 넣는 역할 문자열(예: USER, ADMIN). {@code ROLE_} 접두사는 권한 변환 시 붙인다. */
	public static final String CLAIM_ROLE = "role";

	private final TicketingProperties properties;
	private SecretKey secretKey;

	public JwtTokenService(TicketingProperties properties) {
		this.properties = properties;
	}

	/**
	 * 기동 시 비밀키 길이를 검증하고 HMAC-SHA 키를 만든다.
	 * 운영에서 짧은 키가 들어가면 여기서 즉시 실패한다.
	 */
	@PostConstruct
	void initKey() {
		String secret = properties.getJwt().getSecret();
		if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("ticketing.jwt.secret must be at least 32 bytes (UTF-8) for HS256");
		}
		this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Access 토큰을 발급한다. jti({@link io.jsonwebtoken.JwtBuilder#id(String)})는 블랙리스트·추적에 사용한다.
	 *
	 * @param username 로그인 ID(DB {@code users.username})
	 * @param role     역할 코드(예: USER, ADMIN)
	 */
	public String createAccessToken(String username, String role) {
		Instant now = Instant.now();
		Instant exp = now.plusSeconds(properties.getJwt().getAccessTtlMinutes() * 60);
		String jti = UUID.randomUUID().toString();
		return Jwts.builder()
			.id(jti)
			.subject(username)
			.claim(CLAIM_TYP, TYP_ACCESS)
			.claim(CLAIM_ROLE, role)
			.issuedAt(Date.from(now))
			.expiration(Date.from(exp))
			.signWith(secretKey)
			.compact();
	}

	/**
	 * Refresh 토큰을 발급한다. {@code jti} 는 호출 측에서 생성해 DB {@code refresh_tokens} 와 동일하게 저장해야 한다.
	 */
	public String createRefreshToken(String username, String jti) {
		Instant now = Instant.now();
		Instant exp = now.plusSeconds(properties.getJwt().getRefreshTtlDays() * 24 * 3600);
		return Jwts.builder()
			.id(jti)
			.subject(username)
			.claim(CLAIM_TYP, TYP_REFRESH)
			.issuedAt(Date.from(now))
			.expiration(Date.from(exp))
			.signWith(secretKey)
			.compact();
	}

	/**
	 * 서명이 유효하면 클레임을 돌려준다. 만료된 토큰이어도 {@link ExpiredJwtException#getClaims()} 로 검증된 클레임을 쓴다.
	 * 재발급 분기(만료 여부 판별)에 필요하다. 서명 실패·깨진 형식이면 empty.
	 */
	public java.util.Optional<Claims> parseSignedClaimsLenient(String token) {
		if (token == null || token.isBlank()) {
			return java.util.Optional.empty();
		}
		try {
			return java.util.Optional.of(
				Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload());
		} catch (ExpiredJwtException e) {
			return java.util.Optional.of(e.getClaims());
		} catch (JwtException e) {
			return java.util.Optional.empty();
		}
	}

	/** JWT {@code exp} 를 애플리케이션 기본 타임존(서울) 기준 {@link LocalDateTime} 으로 변환 */
	public LocalDateTime toLocalDateTime(Date date) {
		return LocalDateTime.ofInstant(date.toInstant(), ZoneId.of("Asia/Seoul"));
	}

	/** 현재 시각 기준 만료 여부 */
	public boolean isExpired(Claims claims) {
		return claims.getExpiration() != null && claims.getExpiration().before(new Date());
	}

	/** Refresh 메타 DB에 넣을 새 jti(표준 UUID 문자열) */
	public String newJti() {
		return UUID.randomUUID().toString();
	}
}
