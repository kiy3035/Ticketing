package com.inyoung.ticketing.auth.service;

import java.util.Collections;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.dto.SignupRequest;
import com.inyoung.ticketing.auth.repository.UsersRepository;
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
	private final UsersRepository usersRepository;
	private final PasswordEncoder passwordEncoder;

	// 리포지토리/인코더 주입
	public UsersService(
		UsersRepository usersRepository,
		PasswordEncoder passwordEncoder
	) {
		this.usersRepository = usersRepository;
		this.passwordEncoder = passwordEncoder;
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
		usersRepository.save(account);
	}

	// 스프링 시큐리티 사용자 로딩
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		Users account = usersRepository.findByUsername(username)
			.orElseThrow(() -> new UsernameNotFoundException("User not found"));

		return new User(account.getUsername(), account.getPw(), Collections.emptyList());
	}
}
