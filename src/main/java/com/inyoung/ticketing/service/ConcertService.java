package com.inyoung.ticketing.service;

import java.util.List;
import java.util.stream.Collectors;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.dto.ConcertResponse;
import com.inyoung.ticketing.repository.ConcertRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ConcertService {
	private final ConcertRepository concertRepository;

	public ConcertService(ConcertRepository concertRepository) {
		this.concertRepository = concertRepository;
	}

	@Cacheable(cacheNames = CacheNames.CONCERT_LIST)
	public List<ConcertResponse> listConcerts() {
		return concertRepository.findAll().stream()
			.map(ConcertResponse::new)
			.collect(Collectors.toList());
	}
}
