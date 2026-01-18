package com.inyoung.ticketing.service;

import java.util.List;
import java.util.stream.Collectors;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.dto.SeatResponse;
import com.inyoung.ticketing.repository.SeatRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

// 좌석 조회 서비스
@Service
public class SeatService {
	private final SeatRepository seatRepository;

	// 리포지토리 주입
	public SeatService(SeatRepository seatRepository) {
		this.seatRepository = seatRepository;
	}

	// 콘서트별 좌석 목록 캐시 조회
	@Cacheable(cacheNames = CacheNames.SEAT_LIST, key = "'concert:' + #concertId")
	public List<SeatResponse> listSeats(Long concertId) {
		return seatRepository.findByConcertId(concertId).stream()
			.map(SeatResponse::new)
			.collect(Collectors.toList());
	}
}
