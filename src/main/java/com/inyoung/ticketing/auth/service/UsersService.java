package com.inyoung.ticketing.auth.service;

import java.util.Collections;
import java.util.UUID;
import com.inyoung.ticketing.auth.domain.Users;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.inyoung.ticketing.auth.dto.MyPageResponse;
import com.inyoung.ticketing.auth.dto.SignupRequest;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.notification.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 사용자 계정 서비스 및 인증 사용자 조회.
// - 회원 가입 시 초기 포인트/역할/알림 수단을 설정하고 환영 이메일을 비동기적으로 전송한다.
// - 스프링 시큐리티의 UserDetailsService 를 구현해 세션 기반 인증에 필요한 사용자 정보를 제공한다.
@Service
public class UsersService implements UserDetailsService {
	private static final Logger logger = LoggerFactory.getLogger(UsersService.class);
	private static final long SIGNUP_POINT_BONUS = 10_000_000L;
	private final UsersRepository usersRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailService emailService;

	public UsersService(
		UsersRepository usersRepository,
		PasswordEncoder passwordEncoder,
		EmailService emailService
	) {
		this.usersRepository = usersRepository;
		this.passwordEncoder = passwordEncoder;
		this.emailService = emailService;
	}

	// 회원가입 처리.
	// username 중복 여부를 검사하고, 패스워드는 해시 저장하며,
	// 가입 축하 포인트(SIGNUP_POINT_BONUS)를 지급한다.
	@Transactional
	public void signup(SignupRequest request) {
		if (usersRepository.existsByUsername(request.getUsername())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
		}

		Users account = new Users();
		account.setUsername(request.getUsername());
		account.setPw(passwordEncoder.encode(request.getPassword()));
		account.setEmail(request.getEmail());
		account.setPhone(request.getPhone());
		account.setNotiType(request.getNotificationMethod());
		account.setPoint(SIGNUP_POINT_BONUS);
		String role = request.getRole();
		if (role == null || role.isBlank()) role = "USER";
		if (!"USER".equals(role) && !"SELLER".equals(role)) role = "USER";
		account.setRole(role);
		usersRepository.save(account);

		// 회원가입 성공 시 Gmail로 환영 메일 발송 (실패해도 가입은 유지)
		if (request.getEmail() != null && !request.getEmail().isBlank()) {
			try {
				emailService.sendSignupSuccessEmail(request.getEmail(), request.getUsername());
			} catch (Exception e) {
				logger.warn("Signup success email failed for {}: {}", request.getEmail(), e.getMessage());
			}
		}
	}

	@Transactional(readOnly = true)
	public MyPageResponse loadMyPage(String username) {
		Users account = usersRepository.findByUsername(username)
			.orElseThrow(() -> new UsernameNotFoundException("User not found"));
		Long point = account.getPoint() == null ? 0L : account.getPoint();
		java.time.LocalDateTime createdAt = account.getCreatedAt();
		java.time.OffsetDateTime createdAtOffset = createdAt == null ? null
			: createdAt.atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
		return new MyPageResponse(
			account.getUsername(),
			point,
			createdAtOffset
		);
	}

	@Transactional(readOnly = true)
	public String loadUserRole(String username) {
		Users account = usersRepository.findByUsername(username)
			.orElseThrow(() -> new UsernameNotFoundException("User not found"));
		return account.getRole();
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		Users account = usersRepository.findByUsername(username)
			.orElseThrow(() -> new UsernameNotFoundException("User not found"));

		String role = account.getRole() != null ? account.getRole() : "USER";
		return new User(
			account.getUsername(),
			account.getPw(),
			Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
		);
	}

	/**
	 * <b>소셜 로그인 후</b> IdP(Identity Provider, 여기서는 Google) 계정과 우리 {@code users} 행을 1:1로 연결한다.
	 * <p>
	 * <b>용어</b>
	 * <ul>
	 *   <li>{@code registrationId} : {@code application.properties} 의 {@code spring.security.oauth2.client.registration.google} 에서
	 *       마지막 이름. 예: {@code google}. (같은 DB에 카카오를 붙이면 {@code kakao} 같은 값이 또 생길 수 있음)</li>
	 *   <li>{@code oauthSubject} : IdP 가 발급한 계정 불변 ID. OpenID 에서 흔히 {@code sub} 클레임. 이메일과 별개로 쓴다.</li>
	 * </ul>
	 * <p>
	 * 이미 ({@code oauth_provider}, {@code oauth_subject}) 로 행이 있으면 그대로 반환하고,
	 * 없으면 <b>JIT(Just-In-Time) 가입</b>으로 새 행을 만든다. 별도 "구글 회원가입" 화면은 없다.
	 */
	@Transactional
	public Users provisionOAuthUser(String registrationId, String oauthSubject, String email) {
		return usersRepository.findByOauthProviderAndOauthSubject(registrationId, oauthSubject)
			.orElseGet(() -> createOAuthUser(registrationId, oauthSubject, email));
	}

	/**
	 * OAuth 전용 계정 한 건을 DB에 넣는다.
	 * <ul>
	 *   <li>비밀번호: 우리가 알 수 없으므로 랜덤 문자열을 BCrypt — 폼 로그인으로는 사실상 로그인 불가.</li>
	 *   <li>{@code username}: 서비스 전역에서 쓰는 로그인 ID. {@code g} + {@code sub} 형태로 만들어 {@code users.username} 유니크와 맞춘다.</li>
	 * </ul>
	 */
	private Users createOAuthUser(String registrationId, String oauthSubject, String email) {
		String username = generateOauthUsername(registrationId, oauthSubject);
		String safeEmail = (email != null && !email.isBlank())
			? email
			: "oauth+" + oauthSubject + "@placeholder.local";

		Users account = new Users();
		account.setUsername(username);
		account.setPw(passwordEncoder.encode(UUID.randomUUID().toString()));
		account.setEmail(safeEmail);
		// 기존 DB에 phone 이 NOT NULL인 스키마가 남아 있으면 null INSERT 가 실패한다. 빈 문자열은 "번호 없음"으로 취급(알림은 notiType/email 분기).
		account.setPhone("");
		account.setNotiType("email");
		account.setPoint(SIGNUP_POINT_BONUS);
		account.setRole("USER");
		account.setOauthProvider(registrationId);
		account.setOauthSubject(oauthSubject);
		usersRepository.save(account);

		if (email != null && !email.isBlank()) {
			try {
				emailService.sendSignupSuccessEmail(email, username);
			} catch (Exception e) {
				logger.warn("OAuth signup success email failed for {}: {}", email, e.getMessage());
			}
		}
		return account;
	}

	/**
	 * IdP 마다 {@code sub} 형식이 달라도, 우리 DB {@code username} 컬럼(길이 제한) 안에 들어가도록 문자열을 만든다.
	 * 충돌 시 짧은 랜덤 접미사로 재시도한다.
	 */
	private String generateOauthUsername(String registrationId, String oauthSubject) {
		String base = "google".equals(registrationId)
			? "g" + oauthSubject
			: registrationId + "_" + oauthSubject;
		if (base.length() > 50) {
			base = base.substring(0, 50);
		}
		if (!usersRepository.existsByUsername(base)) {
			return base;
		}
		for (int i = 0; i < 20; i++) {
			String suffix = "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
			String candidate = base.length() + suffix.length() > 50
				? base.substring(0, Math.max(1, 50 - suffix.length())) + suffix
				: base + suffix;
			if (!usersRepository.existsByUsername(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not allocate unique username for OAuth user");
	}
}
