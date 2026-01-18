package com.inyoung.ticketing.service;

import java.util.List;
import java.util.stream.Collectors;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.dto.ConcertResponse;
import com.inyoung.ticketing.repository.ConcertRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

// 콘서트 조회 서비스
@Service
public class ConcertService {
	private final ConcertRepository concertRepository;

	// 리포지토리 주입
	public ConcertService(ConcertRepository concertRepository) {
		this.concertRepository = concertRepository;
	}

	// 콘서트 목록 캐시 조회
	@Cacheable(cacheNames = CacheNames.CONCERT_LIST)
	public List<ConcertResponse> listConcerts() {
		return concertRepository.findAll().stream()
			.map(ConcertResponse::new)
			.collect(Collectors.toList());
	}
}
