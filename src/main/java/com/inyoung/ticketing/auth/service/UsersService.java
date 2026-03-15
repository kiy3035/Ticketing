package com.inyoung.ticketing.auth.service;

import java.util.Collections;
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

// 사용자 계정 서비스 및 인증 사용자 조회
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

	// 회원가입 처리
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

	// 마이페이지 정보 조회
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

	// 로그인 사용자의 역할 조회
	@Transactional(readOnly = true)
	public String loadUserRole(String username) {
		Users account = usersRepository.findByUsername(username)
			.orElseThrow(() -> new UsernameNotFoundException("User not found"));
		return account.getRole();
	}

	// 스프링 시큐리티 사용자 로딩
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
}
