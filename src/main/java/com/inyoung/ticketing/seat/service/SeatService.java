package com.inyoung.ticketing.seat.service;

import java.util.List;
import java.util.Set;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.dto.SeatResponse;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

// 좌석 조회 서비스.
// DB의 좌석 상태(AVAILABLE/RESERVED)와 Redis의 홀드 상태를 결합해
// 프론트에 AVAILABLE / HELD / RESERVED 3가지 상태로 반환한다.
@Service
public class SeatService {
	private final SeatRepository seatRepository;
	private final HoldStore holdStore;
	private final CacheManager cacheManager;

	public SeatService(SeatRepository seatRepository, HoldStore holdStore, CacheManager cacheManager) {
		this.seatRepository = seatRepository;
		this.holdStore = holdStore;
		this.cacheManager = cacheManager;
	}

	// DB 좌석 목록을 조회한 뒤, Redis에서 현재 홀드 중인 좌석 ID를 가져와 상태를 오버레이한다.
	public List<SeatResponse> listSeats(Long concertId) {
		List<Seat> seats = seatRepository.findByConcertId(concertId);
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();
		Set<Long> heldSeatIds = holdStore.findHeldSeatIds(seatIds);
		return seats.stream()
			.map(seat -> {
				// enum switch 표현식: 모든 SeatStatus 케이스를 컴파일러가 검사(누락 시 에러) → 상태 추가 시 안전.
				// DB가 RESERVED면 그대로. 그 외에는 Redis 홀드 집합에 있으면 HELD, 아니면 AVAILABLE 로 화면에 보여 준다.
				SeatStatus status = switch (seat.getStatus()) {
					case RESERVED -> SeatStatus.RESERVED;
					case AVAILABLE, HELD -> heldSeatIds.contains(seat.getId()) ? SeatStatus.HELD : SeatStatus.AVAILABLE;
				};
				return new SeatResponse(seat, status);
			})
			.toList();
	}

	/**
	 * 대기열 UI용: 예매 가능 좌석 수 (전체 − DB 예약 − Redis 홀드).
	 * 컨트롤러가 Repository 를 직접 들지 않게 해 레이어 규칙(ArchUnit)과 응집도를 맞춘다.
	 */
	private long computeAvailableSeats(Long concertId) {
		long totalSeats = seatRepository.countByConcertId(concertId);
		long reserved = seatRepository.countByConcertIdAndStatus(concertId, SeatStatus.RESERVED);
		List<Long> seatIds = seatRepository.findSeatIdsByConcertId(concertId);
		int heldCount = holdStore.findHeldSeatIds(seatIds).size();
		return Math.max(0, totalSeats - reserved - heldCount);
	}

	/**
	 * GET /api/queue/status 폴링용 — 짧은 TTL Redis 캐시(RedisConfig)로 DB 왕복을 줄인다.
	 */
	@Cacheable(cacheNames = CacheNames.AVAILABLE_SEAT_COUNT, key = "#concertId")
	public long countAvailableSeatsForQueueStatus(Long concertId) {
		return computeAvailableSeats(concertId);
	}

	/**
	 * POST /api/queue/enter 의 즉시 입장 판단 등 — 캐시 없이 최신 값.
	 */
	public long countAvailableSeatsForDecision(Long concertId) {
		return computeAvailableSeats(concertId);
	}

	/** 홀드/예약/만료 등 잔여석이 바뀔 때 캐시 무효화 */
	public void evictAvailableSeatCount(Long concertId) {
		if (concertId == null) {
			return;
		}
		var cache = cacheManager.getCache(CacheNames.AVAILABLE_SEAT_COUNT);
		if (cache != null) {
			cache.evict(concertId);
		}
	}
}
