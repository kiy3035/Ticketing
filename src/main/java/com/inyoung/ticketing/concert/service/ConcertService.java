package com.inyoung.ticketing.concert.service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.dto.ConcertResponse;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

// 콘서트 조회 서비스.
// Redis 캐시(@Cacheable)를 활용해 동일 조건의 반복 조회를 줄이고,
// 예매 가능(UPCOMING) / 지난 공연(COMPLETED) 두 가지 뷰를 제공한다.
@Service
public class ConcertService {
	private final ConcertRepository concertRepository;

	public ConcertService(ConcertRepository concertRepository) {
		this.concertRepository = concertRepository;
	}

	/** 콘서트 목록 캐시 조회. past=false면 예매 가능(종료 전), past=true면 지난 공연(종료 후). 오늘 날짜·현재 시간 기준 */
	@Cacheable(cacheNames = CacheNames.CONCERT_LIST, keyGenerator = "concertListKeyGenerator")
	public List<ConcertResponse> listConcerts(String query, String category, boolean past) {
		String trimmed = query == null ? null : query.trim();
		String normalizedQuery = (trimmed == null || trimmed.isBlank()) ? null : trimmed;
		ConcertCategory concertCategory = parseCategory(category);
		Instant now = Instant.now();
		List<com.inyoung.ticketing.concert.domain.Concert> concerts = past
			? concertRepository.searchPastConcerts(concertCategory, normalizedQuery, now)
			: concertRepository.searchUpcomingConcerts(concertCategory, normalizedQuery, now);
		return concerts.stream()
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
