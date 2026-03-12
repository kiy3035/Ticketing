package com.inyoung.ticketing.payment.dto;

/**
 * GET /api/payments/toss-client-key 응답 DTO.
 * Map 반환 시 Solapi SDK(Kotlin) 직렬화 컨버터가 선택되며 ApiResponse 래핑 후 ClassCastException 발생 → DTO 반환으로 Jackson 직렬화 사용.
 */
public class TossClientKeyResponse {
	private final String clientKey;

	public TossClientKeyResponse(String clientKey) {
		this.clientKey = clientKey != null ? clientKey : "";
	}

	public String getClientKey() {
		return clientKey;
	}
}
