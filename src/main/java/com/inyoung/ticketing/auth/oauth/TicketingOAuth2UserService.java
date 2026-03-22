package com.inyoung.ticketing.auth.oauth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.service.UsersService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class TicketingOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
	private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
	private final UsersService usersService;

	public TicketingOAuth2UserService(UsersService usersService) {
		this.usersService = usersService;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) {
		OAuth2User loaded = delegate.loadUser(userRequest);
		String registrationId = userRequest.getClientRegistration().getRegistrationId();
		String subject = loaded.getAttribute("sub");
		if (subject == null || subject.isBlank()) {
			throw new IllegalStateException("OAuth2 userinfo missing sub");
		}
		String email = loaded.getAttribute("email");
		Users user = usersService.provisionOAuthUser(registrationId, subject, email);
		String role = user.getRole() != null ? user.getRole() : "USER";
		GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role);

		Map<String, Object> attributes = new HashMap<>(loaded.getAttributes());
		attributes.put(OAuth2UserAttributeNames.INTERNAL_USERNAME, user.getUsername());

		return new DefaultOAuth2User(
			Collections.singleton(authority),
			attributes,
			OAuth2UserAttributeNames.INTERNAL_USERNAME
		);
	}
}
