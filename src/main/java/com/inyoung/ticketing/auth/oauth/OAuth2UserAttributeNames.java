package com.inyoung.ticketing.auth.oauth;

/**
 * DefaultOAuth2User 의 nameAttributeKey 로 사용. SecurityContext.getName() 및
 * OAuth2User.getName()이 DB username 과 일치하도록 한다.
 */
public final class OAuth2UserAttributeNames {
	public static final String INTERNAL_USERNAME = "internal_username";

	private OAuth2UserAttributeNames() {
	}
}
