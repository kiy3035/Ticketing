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

/**
 * OAuth2 <b>일반</b> UserInfo 엔드포인트용 사용자 매핑 (OIDC가 아닌 순수 OAuth2 경로).
 * <p>
 * <b>OAuth2 로그인이 도는 순서(개념)</b>
 * <ol>
 *   <li>사용자가 "Google로 로그인" → 브라우저가 Google 로그인/동의 화면으로 이동 (리다이렉트).</li>
 *   <li>동의 후 Google이 브라우저를 우리 앱 콜백 URL로 보냄. URL에 <b>Authorization Code</b>가 붙어 있음.</li>
 *   <li>서버(Spring Security)가 그 코드로 Google <b>토큰 엔드포인트</b>에 요청 → <b>액세스 토큰</b>을 받음.</li>
 *   <li>액세스 토큰으로 Google <b>UserInfo API</b>를 호출 → 이 클래스의 {@link #loadUser}가 호출되는 시점은
 *       "UserInfo 응답을 받은 직후, 그걸 Spring Security용 {@link OAuth2User}로 바꾸기 전"이다.</li>
 * </ol>
 * <p>
 * <b>Google + openid 스코프</b>를 쓰는 경우 Spring은 OIDC 경로를 타므로, 실제 Google 로그인에서는
 * {@link TicketingOidcUserService}가 사용된다. 이 클래스는 다른 IdP/설정에서 순수 OAuth2 UserInfo만 쓸 때를 대비한 것이다.
 */
@Service
public class TicketingOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
	/** Spring 기본 구현: 토큰으로 UserInfo HTTP 호출까지 해 줌. */
	private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
	private final UsersService usersService;

	public TicketingOAuth2UserService(UsersService usersService) {
		this.usersService = usersService;
	}

	/**
	 * UserInfo에서 식별자·이메일을 꺼내 우리 DB 사용자와 연결하고, SecurityContext에 올라갈 {@link OAuth2User}를 만든다.
	 */
	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) {
		// 1) Google 등 IdP가 준 표준 클레임 맵 (sub, email 등)
		OAuth2User loaded = delegate.loadUser(userRequest);
		// registrationId 예: application.properties 의 spring.security.oauth2.client.registration.<이름> → 여기서는 "google"
		String registrationId = userRequest.getClientRegistration().getRegistrationId();
		// sub: IdP가 부여한 계정 불변 ID. 이메일이 바뀌어도 보통 sub 은 그대로인 경우가 많다.
		String subject = loaded.getAttribute("sub");
		if (subject == null || subject.isBlank()) {
			throw new IllegalStateException("OAuth2 userinfo missing sub");
		}
		String email = loaded.getAttribute("email");
		// 2) DB에 (provider, subject)로 이미 있으면 조회, 없으면 JIT 가입
		Users user = usersService.provisionOAuthUser(registrationId, subject, email);
		String role = user.getRole() != null ? user.getRole() : "USER";
		GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role);

		// 3) IdP 클레임 + 우리 username 을 합친 맵. nameAttributeKey 로 internal_username 을 쓰면 getName() 이 DB username 과 일치
		Map<String, Object> attributes = new HashMap<>(loaded.getAttributes());
		attributes.put(OAuth2UserAttributeNames.INTERNAL_USERNAME, user.getUsername());

		return new DefaultOAuth2User(
			Collections.singleton(authority),
			attributes,
			OAuth2UserAttributeNames.INTERNAL_USERNAME
		);
	}
}
