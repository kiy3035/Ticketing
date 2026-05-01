package com.inyoung.ticketing.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.dto.TokenPairResponse;
import com.inyoung.ticketing.auth.jwt.repository.RefreshTokenRepository;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.support.IntegrationTestBase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * JWT 인증 통합 테스트.
 *
 * <p><b>검증 대상</b>: JWT stateless 인증의 약점(로그아웃 즉시 무효화 불가)을 보완하는
 * Redis 블랙리스트 + DB Refresh `revoked` 마킹 메커니즘이 실제로 동작하는지,
 * 그리고 4-case 분기 중 Case 2 자동 재발급이 의도대로 흐르는지를 본다.</p>
 *
 * <p>실제 MySQL + Redis 컨테이너(Testcontainers)에서 보호된 엔드포인트
 * ({@code GET /api/reservations/me}) 를 실제 HTTP 호출로 검증한다.</p>
 */
class JwtAuthenticationIntegrationTest extends IntegrationTestBase {

	@Autowired private JwtTokenService jwtTokenService;
	@Autowired private JwtTokenIssueService jwtTokenIssueService;
	@Autowired private JwtAuthenticationService jwtAuthenticationService;
	@Autowired private RefreshTokenPersistenceService refreshTokenPersistenceService;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private UsersRepository usersRepository;
	@Autowired private TicketingProperties ticketingProperties;
	@Autowired private StringRedisTemplate redisTemplate;
	@Autowired private TestRestTemplate restTemplate;

	@LocalServerPort private int port;

	private static final String PROTECTED_PATH = "/api/reservations/me";
	private static final String USER_A = "jwt-test-alice";
	private static final String USER_B = "jwt-test-bob";

	@BeforeEach
	void setUp() {
		// 이전 테스트 잔여 데이터 정리 (Testcontainers 가 클래스 간 공유)
		refreshTokenRepository.deleteAll();
		usersRepository.findByUsername(USER_A).ifPresent(usersRepository::delete);
		usersRepository.findByUsername(USER_B).ifPresent(usersRepository::delete);
		// 블랙리스트 키 정리 (jwt:bl:* 만 골라서)
		var keys = redisTemplate.keys("jwt:bl:*");
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 1: 로그아웃 → 같은 Access 로 보호된 API 호출 → 401, Redis 블랙리스트 키 존재
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("로그아웃 → Access 즉시 차단 (Redis 블랙리스트 등록 + 보호 API 401)")
	void logout_blacklistsAccessJti_andRejectsSubsequentRequests() {
		// given: 정상 로그인으로 토큰 쌍 발급
		createUser(USER_A);
		TokenPairResponse pair = jwtTokenIssueService.issueForUsername(USER_A);
		String accessJti = jwtTokenService.parseSignedClaimsLenient(pair.accessToken()).orElseThrow().getId();

		// when: 로그아웃 (Access jti 블랙리스트 등록 + Refresh DB revoke)
		jwtAuthenticationService.logout("Bearer " + pair.accessToken(), pair.refreshToken());

		// then: Redis 에 jwt:bl:{jti} 키가 존재 (TTL = Access 잔여 시간)
		assertThat(redisTemplate.hasKey("jwt:bl:" + accessJti))
			.as("로그아웃된 Access jti 는 Redis 블랙리스트에 등록되어야 한다")
			.isTrue();

		// then: 같은 Access 로 보호된 API 호출 → 401
		ResponseEntity<String> response = callProtected(pair.accessToken(), pair.refreshToken());
		assertThat(response.getStatusCode())
			.as("로그아웃된 Access 로 호출하면 401")
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 2: 로그아웃 후 (만료된 Access + 살아있는 Refresh) 로 재발급 시도 → 401
	//   Refresh JWT 자체는 서명·만료 OK 지만 DB revoked = true 라 차단되어야 함.
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("로그아웃 후 Refresh 로 재발급 시도 → 401 (DB revoked)")
	void logout_revokesRefresh_andBlocksAccessReissue() {
		// given: 토큰 쌍 발급 + 로그아웃 (Refresh DB revoke 됨)
		Users user = createUser(USER_A);
		TokenPairResponse pair = jwtTokenIssueService.issueForUsername(USER_A);
		jwtAuthenticationService.logout("Bearer " + pair.accessToken(), pair.refreshToken());

		// when: 만료된 Access + 같은(이미 revoke 된) Refresh 로 보호 API 호출
		String expiredAccess = mintExpiredAccessToken(USER_A, user.getRole());
		ResponseEntity<String> response = callProtected(expiredAccess, pair.refreshToken());

		// then: Case 2 경로 진입 후 DB 검증 실패로 401
		assertThat(response.getStatusCode())
			.as("revoke 된 Refresh 로는 새 Access 가 발급되지 않아야 한다")
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 3: Case 2 자동 재발급 — 만료 Access + 유효 Refresh → 200 + X-New-Access-Token
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("만료 Access + 유효 Refresh → 새 Access 헤더(X-New-Access-Token)로 자동 재발급")
	void case2_reissuesAccess_viaResponseHeader() {
		// given: 정상 로그인 후 Refresh 만 살아있고 Access 는 만료된 상태를 모사
		Users user = createUser(USER_A);
		TokenPairResponse pair = jwtTokenIssueService.issueForUsername(USER_A);
		String expiredAccess = mintExpiredAccessToken(USER_A, user.getRole());

		// when: 만료 Access + 유효 Refresh 로 보호 API 호출
		ResponseEntity<String> response = callProtected(expiredAccess, pair.refreshToken());

		// then: 200 OK + 응답 헤더에 새 Access 가 실려있고, 그게 유효한 JWT 여야 한다
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String newAccess = response.getHeaders().getFirst("X-New-Access-Token");
		assertThat(newAccess)
			.as("Case 2 에서는 X-New-Access-Token 헤더로 새 Access 가 내려와야 한다")
			.isNotBlank();
		assertThat(jwtTokenService.parseSignedClaimsLenient(newAccess))
			.as("새 Access 는 서명 검증을 통과하는 유효한 JWT 여야 한다")
			.isPresent();
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 4: 다른 사용자의 Refresh 도용 → 401 (sub 불일치)
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("사용자 A 의 Access + 사용자 B 의 Refresh → 401 (sub 불일치)")
	void mismatchedSubject_isRejected() {
		// given: 두 명의 사용자 각각 정상 토큰 발급
		createUser(USER_A);
		createUser(USER_B);
		TokenPairResponse pairA = jwtTokenIssueService.issueForUsername(USER_A);
		TokenPairResponse pairB = jwtTokenIssueService.issueForUsername(USER_B);

		// when: A 의 Access + B 의 Refresh 로 호출 (토큰 짜깁기 공격)
		ResponseEntity<String> response = callProtected(pairA.accessToken(), pairB.refreshToken());

		// then: subject 가 다르므로 인증 거부
		assertThat(response.getStatusCode())
			.as("Access·Refresh 의 subject 가 다르면 즉시 401")
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 5: 블랙리스트 TTL 자동 만료 — 잔여 시간이 지나면 키 자동 삭제
	//   메모리 무한 누적 방지(운영 안정성) 어필.
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("짧은 TTL Access 블랙리스트 등록 → TTL 경과 후 키 자동 삭제")
	void blacklistKey_expiresAutomatically_byAccessRemainingTtl() throws InterruptedException {
		// given: 2초 뒤 만료되는 Access 의 jti 를 블랙리스트에 등록
		String jti = UUID.randomUUID().toString();
		Instant expiresAt = Instant.now().plusSeconds(2);

		// when
		// 실제 TokenBlacklistService 빈을 통해 등록 (TTL = 잔여 만료시간)
		// (this 클래스에 inject 안 한 이유: 다른 시나리오에선 직접 호출 안 함)
		// TODO: TokenBlacklistService 를 @Autowired 로 추가하고 blacklistAccessJti(jti, expiresAt) 호출
		// 또는 redisTemplate 으로 직접 SET EX 검증

		// then-immediate: 등록 직후엔 키 존재
		// then-after-ttl: 약 3초 대기 후 키가 사라져야 함
		// Awaitility 권장 — Thread.sleep 은 fragile

		// 작성 시 헬퍼 채우기:
		// tokenBlacklistService.blacklistAccessJti(jti, expiresAt);
		// assertThat(redisTemplate.hasKey("jwt:bl:" + jti)).isTrue();
		// Thread.sleep(2500);
		// assertThat(redisTemplate.hasKey("jwt:bl:" + jti)).isFalse();
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 헬퍼
	// ─────────────────────────────────────────────────────────────────────────────

	private Users createUser(String username) {
		Users u = new Users();
		u.setUsername(username);
		u.setPw("$2a$10$dummy.bcrypt.hash.placeholder.value.for.test.only.aaaaaaaa");
		u.setEmail(username + "@test.com");
		u.setPhone("01000000000");
		u.setNotiType("sms");
		u.setRole("USER");
		u.setPoint(0L);
		return usersRepository.save(u);
	}

	/**
	 * 같은 비밀키로 서명하되 exp 가 과거인 Access 토큰을 발급한다.
	 * Case 2(만료 Access + 유효 Refresh) 시나리오 검증용.
	 */
	private String mintExpiredAccessToken(String username, String role) {
		SecretKey key = Keys.hmacShaKeyFor(
			ticketingProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
		Instant now = Instant.now();
		return Jwts.builder()
			.id(UUID.randomUUID().toString())
			.subject(username)
			.claim(JwtTokenService.CLAIM_TYP, JwtTokenService.TYP_ACCESS)
			.claim(JwtTokenService.CLAIM_ROLE, role)
			.issuedAt(Date.from(now.minusSeconds(120)))
			.expiration(Date.from(now.minusSeconds(60)))
			.signWith(key)
			.compact();
	}

	/**
	 * 보호된 엔드포인트에 Access·Refresh 두 헤더를 실어 GET 호출.
	 */
	private ResponseEntity<String> callProtected(String access, String refresh) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", "Bearer " + access);
		headers.set("X-Refresh-Token", refresh);
		return restTemplate.exchange(
			"http://localhost:" + port + PROTECTED_PATH,
			HttpMethod.GET,
			new HttpEntity<>(headers),
			String.class
		);
	}
}
