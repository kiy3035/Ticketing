package com.inyoung.ticketing.seller.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.reservation.domain.Reservation;
import com.inyoung.ticketing.reservation.repository.ReservationRepository;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import com.inyoung.ticketing.seat.service.SeatService;
import com.inyoung.ticketing.cache.CacheNames;
import com.inyoung.ticketing.seller.dto.*;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SellerService {
	private final UsersRepository usersRepository;
	private final ConcertRepository concertRepository;
	private final SeatRepository seatRepository;
	private final ReservationRepository reservationRepository;
	private final PaymentRepository paymentRepository;
	private final CacheManager cacheManager;
	private final SeatService seatService;

	public SellerService(
		UsersRepository usersRepository,
		ConcertRepository concertRepository,
		SeatRepository seatRepository,
		ReservationRepository reservationRepository,
		PaymentRepository paymentRepository,
		CacheManager cacheManager,
		SeatService seatService
	) {
		this.usersRepository = usersRepository;
		this.concertRepository = concertRepository;
		this.seatRepository = seatRepository;
		this.reservationRepository = reservationRepository;
		this.paymentRepository = paymentRepository;
		this.cacheManager = cacheManager;
		this.seatService = seatService;
	}

	private Users getSeller(String username) {
		return usersRepository.findByUsername(username)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
	}

	private Concert getConcertOwnedBy(Long concertId, Long sellerId) {
		Concert c = concertRepository.findById(concertId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Concert not found"));
		if (c.getSeller() == null || !c.getSeller().getId().equals(sellerId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your concert");
		}
		return c;
	}

	private static ConcertStatus computeStatus(Concert c) {
		if (c.getStatus() == ConcertStatus.CANCELLED) return ConcertStatus.CANCELLED;
		Instant now = Instant.now();
		return now.isBefore(c.getConcertAt()) ? ConcertStatus.UPCOMING : ConcertStatus.COMPLETED;
	}

	/** DB에 직접 넣거나 mysqldump 복원 시에도 목록이 갱신되도록, 공연 변경 시마다 콘서트 목록 캐시를 비운다. */
	private void evictConcertListCache() {
		var cache = cacheManager.getCache(CacheNames.CONCERT_LIST);
		if (cache != null) {
			cache.clear();
		}
	}

	@Transactional(readOnly = true)
	public List<SellerConcertResponse> getMyConcerts(String username) {
		Users seller = getSeller(username);
		Long sellerId = seller.getId();
		List<Concert> concerts = concertRepository.findBySeller_IdOrderByCreatedAtDesc(sellerId);
		java.time.ZoneId seoul = java.time.ZoneId.of("Asia/Seoul");
		return concerts.stream()
			.map(c -> {
				int seatCount = seatRepository.findByConcertId(c.getId()).size();
				long reservedCount = seatRepository.countByConcertIdAndStatus(c.getId(), SeatStatus.RESERVED);
				Long revenue = paymentRepository.sumAmountByConcertIdAndStatus(c.getId());
				java.time.OffsetDateTime createdAtOffset = c.getCreatedAt() == null ? null
					: c.getCreatedAt().atZone(seoul).toOffsetDateTime();
				return new SellerConcertResponse(
					c.getId(), c.getTitle(), c.getVenue(), c.getConcertAt(),
					computeStatus(c), c.getCategory(), createdAtOffset,
					seatCount, reservedCount, revenue
				);
			})
			.toList();
	}

	@Transactional
	public SellerConcertResponse createConcert(String username, SellerConcertCreateRequest request) {
		Users seller = getSeller(username);
		Concert c = new Concert();
		c.setTitle(request.getTitle());
		c.setVenue(request.getVenue());
		c.setConcertAt(request.getConcertAt());
		c.setCategory(request.getCategory());
		c.setStatus(ConcertStatus.UPCOMING);
		c.setSeller(seller);
		Concert saved = concertRepository.save(c);
		evictConcertListCache();
		java.time.OffsetDateTime createdAtOffset = saved.getCreatedAt() == null ? null
			: saved.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
		return new SellerConcertResponse(
			saved.getId(), saved.getTitle(), saved.getVenue(), saved.getConcertAt(),
			ConcertStatus.UPCOMING, saved.getCategory(), createdAtOffset,
			0, 0L, 0L
		);
	}

	@Transactional(readOnly = true)
	public SellerConcertResponse getConcert(String username, Long concertId) {
		Users seller = getSeller(username);
		Concert c = getConcertOwnedBy(concertId, seller.getId());
		int seatCount = seatRepository.findByConcertId(c.getId()).size();
		long reservedCount = seatRepository.countByConcertIdAndStatus(c.getId(), SeatStatus.RESERVED);
		Long revenue = paymentRepository.sumAmountByConcertIdAndStatus(c.getId());
		java.time.OffsetDateTime createdAtOffset = c.getCreatedAt() == null ? null
			: c.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
		return new SellerConcertResponse(
			c.getId(), c.getTitle(), c.getVenue(), c.getConcertAt(),
			computeStatus(c), c.getCategory(), createdAtOffset,
			seatCount, reservedCount, revenue
		);
	}

	@Transactional
	public SellerConcertResponse updateConcert(String username, Long concertId, SellerConcertUpdateRequest request) {
		Concert c = getConcertOwnedBy(concertId, getSeller(username).getId());
		if (request.getTitle() != null && !request.getTitle().isBlank()) c.setTitle(request.getTitle());
		if (request.getVenue() != null && !request.getVenue().isBlank()) c.setVenue(request.getVenue());
		if (request.getConcertAt() != null) c.setConcertAt(request.getConcertAt());
		if (request.getCategory() != null) c.setCategory(request.getCategory());
		concertRepository.save(c);
		evictConcertListCache();
		return getConcert(username, concertId);
	}

	@Transactional
	public void cancelConcert(String username, Long concertId) {
		Concert c = getConcertOwnedBy(concertId, getSeller(username).getId());
		c.setStatus(ConcertStatus.CANCELLED);
		concertRepository.save(c);
		evictConcertListCache();
	}

	@Transactional(readOnly = true)
	public List<SellerSeatResponse> getSeats(String username, Long concertId) {
		Concert c = getConcertOwnedBy(concertId, getSeller(username).getId());
		return seatRepository.findByConcertId(c.getId()).stream()
			.map(s -> new SellerSeatResponse(s.getId(), s.getSection(), s.getSeatNo(), s.getPrice(), s.getStatus()))
			.toList();
	}

	@Transactional
	public List<SellerSeatResponse> createSeats(String username, Long concertId, SellerSeatCreateRequest request) {
		Concert c = getConcertOwnedBy(concertId, getSeller(username).getId());
		int from = request.getSeatNoFrom();
		int to = request.getSeatNoTo();
		if (from > to) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seatNoFrom must be <= seatNoTo");
		if (to - from + 1 > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max 500 seats per request");
		for (int no = from; no <= to; no++) {
			Seat seat = new Seat();
			seat.setConcert(c);
			seat.setSection(request.getSection());
			seat.setSeatNo(String.valueOf(no));
			seat.setPrice(request.getPrice());
			seat.setStatus(SeatStatus.AVAILABLE);
			seatRepository.save(seat);
		}
		seatService.evictQueueStatusAvailableSeats(c.getId());
		return getSeats(username, concertId);
	}

	@Transactional(readOnly = true)
	public List<SellerReservationResponse> getReservations(String username, Long concertId) {
		getConcertOwnedBy(concertId, getSeller(username).getId());
		java.time.ZoneId seoul = java.time.ZoneId.of("Asia/Seoul");
		return reservationRepository.findByConcert_IdOrderByReservedAtDesc(concertId).stream()
			.map(r -> {
				java.time.OffsetDateTime reservedAtOffset = r.getReservedAt() == null ? null
					: r.getReservedAt().atZone(seoul).toOffsetDateTime();
				return new SellerReservationResponse(
					r.getId(), r.getUserId(),
					r.getSeat().getSection(), r.getSeat().getSeatNo(), r.getSeat().getPrice(),
					r.getStatus(), reservedAtOffset
				);
			})
			.toList();
	}

	@Transactional(readOnly = true)
	public SellerSalesSummaryResponse getSales(String username, Long concertId) {
		getConcertOwnedBy(concertId, getSeller(username).getId());
		Long totalRevenue = paymentRepository.sumAmountByConcertIdAndStatus(concertId);
		List<Payment> payments = paymentRepository.findByConcertIdAndStatus(concertId, PaymentStatus.COMPLETED,
			org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
		java.time.ZoneId seoul = java.time.ZoneId.of("Asia/Seoul");
		List<SellerPaymentResponse> list = payments.stream()
			.map(p -> new SellerPaymentResponse(
				p.getPaymentKey(), p.getUserId(), p.getAmount(), p.getStatus().name(),
				p.getCompletedAt() != null ? p.getCompletedAt().atZone(seoul).toOffsetDateTime() : null
			))
			.toList();
		return new SellerSalesSummaryResponse(totalRevenue, list.size(), list);
	}
}
