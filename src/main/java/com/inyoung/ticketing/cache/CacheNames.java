package com.inyoung.ticketing.cache;

// 캐시 이름 상수 모음
public final class CacheNames {
	public static final String CONCERT_LIST = "concerts";
	public static final String SEAT_LIST = "seats";
	/** GET /api/queue/status 폴링용 잔여석 수 — Redis 캐시, 짧은 TTL(RedisConfig) */
	public static final String AVAILABLE_SEAT_COUNT = "availableSeatCount";

	// 인스턴스화 방지
	private CacheNames() {
	}
}
