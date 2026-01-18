package com.inyoung.ticketing.service;

import java.util.Collections;
import com.inyoung.ticketing.domain.UserAccount;
import com.inyoung.ticketing.dto.SignupRequest;
import com.inyoung.ticketing.repository.UserAccountRepository;
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
public class UserAccountService implements UserDetailsService {
	private final UserAccountRepository userAccountRepository;
	private final PasswordEncoder passwordEncoder;

	// 리포지토리/인코더 주입
	public UserAccountService(
		UserAccountRepository userAccountRepository,
		PasswordEncoder passwordEncoder
	) {
		this.userAccountRepository = userAccountRepository;
		this.passwordEncoder = passwordEncoder;
	}

	// 회원가입 처리
	@Transactional
	public void signup(SignupRequest request) {
		if (userAccountRepository.existsByUsername(request.getUsername())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
		}

		UserAccount account = new UserAccount();
		account.setUsername(request.getUsername());
		account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
		userAccountRepository.save(account);
	}

	// 스프링 시큐리티 사용자 로딩
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		UserAccount account = userAccountRepository.findByUsername(username)
			.orElseThrow(() -> new UsernameNotFoundException("User not found"));

		return new User(account.getUsername(), account.getPasswordHash(), Collections.emptyList());
	}
}
