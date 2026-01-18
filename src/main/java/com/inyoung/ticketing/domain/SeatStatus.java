package com.inyoung.ticketing.domain;

// 좌석 상태(예약 흐름에 따른 상태 전이)
public enum SeatStatus {
	AVAILABLE,
	HELD,
	RESERVED
}
