package com.inyoung.ticketing.cache;

// 캐시 이름 상수 모음
public final class CacheNames {
	public static final String CONCERT_LIST = "concerts";
	public static final String SEAT_LIST = "seats";

	/** {@code GET /api/queue/status} 응답의 잔여석 집계. 공연(concertId) 단위, 짧은 TTL + 이벤트 시 evict */
	public static final String QUEUE_STATUS_AVAILABLE_SEATS = "queueStatusAvailableSeats";

	// 인스턴스화 방지
	private CacheNames() {
	}
}
