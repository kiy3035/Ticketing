package com.inyoung.ticketing.auth.oauth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.service.UsersService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * <b>OIDC(OpenID Connect)</b> 로그인 시 Spring Security가 호출하는 사용자 매핑 구현.
 * <p>
 * <b>OAuth2 와 OIDC 차이 (이 프로젝트에서 중요한 것만)</b>
 * <ul>
 *   <li><b>OAuth2</b>: "이 앱이 Google API 등에 <b>대신 접근</b>해도 되는가"를 위한 위임·토큰 프레임워크.</li>
 *   <li><b>OIDC</b>: OAuth2 위에 "로그인한 사용자 <b>정체(identity)</b>"를 표준 클레임으로 실어 주는 확장.
 *       {@code openid} 스코프를 요청하면 IdP는 <b>ID 토큰(JWT)</b> + UserInfo 등을 준다.</li>
 *   <li>Google 로그인 설정에 {@code openid,profile,email} 이 있으면 Spring 은 <b>OIDC 경로</b>를 탄다.</li>
 *   <li>이때 사용되는 콜백 타입은 {@link OidcUserRequest} / {@link OidcUser} 이고,
 *       {@link TicketingOAuth2UserService}({@link OAuth2UserRequest}용)는 <b>호출되지 않는다</b>.
 *       그래서 이 클래스를 반드시 {@code oidcUserService(...)} 로 등록해야 DB 연동·내부 username 이 동작한다.</li>
 * </ul>
 */
@Service
public class TicketingOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
	/** Spring 기본 OIDC: ID 토큰 검증 + UserInfo 조합까지 수행. */
	private final OAuth2UserService<OidcUserRequest, OidcUser> delegate = new OidcUserService();
	private final UsersService usersService;

	public TicketingOidcUserService(UsersService usersService) {
		this.usersService = usersService;
	}

	/**
	 * IdP가 준 OIDC 사용자 정보를 우리 DB {@code users} 와 연결한 뒤, 세션에 올라갈 {@link OidcUser}를 만든다.
	 */
	@Override
	public OidcUser loadUser(OidcUserRequest userRequest) {
		// 표준 OIDC 사용자(클레임: sub, email, name 등 + ID 토큰)
		OidcUser oidcUser = delegate.loadUser(userRequest);
		String registrationId = userRequest.getClientRegistration().getRegistrationId();
		String subject = oidcUser.getSubject();
		if (subject == null || subject.isBlank()) {
			throw new IllegalStateException("OIDC userinfo missing sub");
		}
		String email = oidcUser.getEmail();
		Users user = usersService.provisionOAuthUser(registrationId, subject, email);
		String role = user.getRole() != null ? user.getRole() : "USER";
		GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role);

		// OIDC 클레임 맵에 internal_username 추가 → DefaultOidcUser 의 getName() 이 DB username 반환
		Map<String, Object> claims = new HashMap<>(oidcUser.getAttributes());
		claims.put(OAuth2UserAttributeNames.INTERNAL_USERNAME, user.getUsername());

		OidcUserInfo userInfo = new OidcUserInfo(claims);
		return new DefaultOidcUser(
			Collections.singleton(authority),
			oidcUser.getIdToken(),
			userInfo,
			OAuth2UserAttributeNames.INTERNAL_USERNAME
		);
	}
}
