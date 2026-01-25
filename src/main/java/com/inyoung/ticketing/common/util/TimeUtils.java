package com.inyoung.ticketing.common.util;

import java.time.OffsetDateTime;
import java.time.ZoneId;

// 시간 유틸리티
public final class TimeUtils {
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private TimeUtils() {
	}

	public static OffsetDateTime nowKst() {
		return OffsetDateTime.now(KST);
	}
}
