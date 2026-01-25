package com.inyoung.ticketing.concert.service;

import java.util.List;
import java.util.stream.Collectors;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.dto.ConcertResponse;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
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
	@Cacheable(cacheNames = CacheNames.CONCERT_LIST, keyGenerator = "concertListKeyGenerator")
	public List<ConcertResponse> listConcerts(String query, String category) {
		String trimmed = query == null ? null : query.trim();
		String normalizedQuery = (trimmed == null || trimmed.isBlank()) ? null : trimmed;
		ConcertCategory concertCategory = parseCategory(category);
		return concertRepository.searchConcerts(concertCategory, normalizedQuery).stream()
			.map(ConcertResponse::new)
			.collect(Collectors.toList());
	}

	private ConcertCategory parseCategory(String category) {
		if (category == null || category.isBlank() || "ALL".equalsIgnoreCase(category)) {
			return null;
		}
		try {
			return ConcertCategory.valueOf(category.toUpperCase());
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
