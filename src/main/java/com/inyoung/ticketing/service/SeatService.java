package com.inyoung.ticketing.service;

import java.util.List;
import java.util.stream.Collectors;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.dto.SeatResponse;
import com.inyoung.ticketing.repository.SeatRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class SeatService {
	private final SeatRepository seatRepository;

	public SeatService(SeatRepository seatRepository) {
		this.seatRepository = seatRepository;
	}

	@Cacheable(cacheNames = CacheNames.SEAT_LIST, key = "'concert:' + #concertId")
	public List<SeatResponse> listSeats(Long concertId) {
		return seatRepository.findByConcertId(concertId).stream()
			.map(SeatResponse::new)
			.collect(Collectors.toList());
	}
}
