package com.inyoung.ticketing.hold.dto;

import java.time.Instant;

/**
 * 사용자별 "예약 중" 홀드 목록용 응답 DTO (공연·좌석 정보 포함).
 * 필드가 많을수록 record 로 두면 보일러플레이트가 줄고, 서비스에서는 {@code new HoldItemResponse(...)} 한 번에 조립하면 된다.
 */
public record HoldItemResponse(
	String holdToken,
	Long concertId,
	String concertTitle,
	String venue,
	Instant concertAt,
	Long seatId,
	String section,
	String seatNo,
	Long price,
	Instant expiresAt
) {
}
