package com.inyoung.ticketing.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.notification.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UsersServiceOAuthProvisioningTest {

	@Mock
	private UsersRepository usersRepository;
	@Mock
	private EmailService emailService;
	private PasswordEncoder passwordEncoder;
	private UsersService usersService;

	@BeforeEach
	void setUp() {
		passwordEncoder = new BCryptPasswordEncoder();
		usersService = new UsersService(usersRepository, passwordEncoder, emailService);
	}

	@Test
	void provisionOAuthUser_returnsExisting_whenLinked() {
		Users existing = new Users();
		existing.setUsername("g123");
		existing.setOauthProvider("google");
		existing.setOauthSubject("123");
		when(usersRepository.findByOauthProviderAndOauthSubject("google", "123")).thenReturn(Optional.of(existing));

		Users result = usersService.provisionOAuthUser("google", "123", "x@y.com");

		assertThat(result.getUsername()).isEqualTo("g123");
		verify(usersRepository).findByOauthProviderAndOauthSubject("google", "123");
	}

	@Test
	void provisionOAuthUser_createsUser_withExpectedUsernameAndOauthFields() {
		when(usersRepository.findByOauthProviderAndOauthSubject("google", "999")).thenReturn(Optional.empty());
		when(usersRepository.existsByUsername("g999")).thenReturn(false);
		when(usersRepository.save(any(Users.class))).thenAnswer(inv -> inv.getArgument(0));

		Users result = usersService.provisionOAuthUser("google", "999", "user@example.com");

		assertThat(result.getUsername()).isEqualTo("g999");
		assertThat(result.getOauthProvider()).isEqualTo("google");
		assertThat(result.getOauthSubject()).isEqualTo("999");
		assertThat(result.getEmail()).isEqualTo("user@example.com");
		assertThat(result.getPhone()).isEmpty();
		assertThat(result.getNotiType()).isEqualTo("email");
		assertThat(result.getPw()).isNotBlank();
		verify(usersRepository).save(any(Users.class));
	}
}
