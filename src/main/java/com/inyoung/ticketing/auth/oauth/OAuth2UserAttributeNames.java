package com.inyoung.ticketing.auth.oauth;

/**
 * OAuth2/OIDC 로그인 후 Spring Security가 만드는 Principal({@code OAuth2User} / {@code OidcUser})에서
 * "로그인한 사람의 이름"으로 쓸 속성 키 이름을 정의한다.
 * <p>
 * <b>왜 별도 키가 필요한가?</b>
 * <ul>
 *   <li>Google이 주는 기본 "이름"은 보통 {@code sub}(사용자 고유 ID, 숫자 문자열)이다.</li>
 *   <li>우리 서비스의 예매·알림 API는 예전부터 {@code Authentication#getName()} = DB의 {@code users.username} 이라고 가정했다.</li>
 *   <li>그래서 IdP가 준 {@code sub} 대신, 우리 DB에 저장된 {@code username}(예: g123...)을 속성 맵에 넣고,
 *       {@code DefaultOAuth2User}/{@code DefaultOidcUser}의 nameAttributeKey를 이 키로 지정해
 *       {@code getName()}이 항상 내부 아이디를 반환하게 맞춘다.</li>
 * </ul>
 */
public final class OAuth2UserAttributeNames {
	/** 속성 맵에 넣는 우리 서비스용 로그인 아이디(DB {@code users.username})의 키. */
	public static final String INTERNAL_USERNAME = "internal_username";

	private OAuth2UserAttributeNames() {
	}
}
