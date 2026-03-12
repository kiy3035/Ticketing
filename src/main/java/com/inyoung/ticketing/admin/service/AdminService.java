package com.inyoung.ticketing.admin.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

import com.inyoung.ticketing.admin.dto.AdminPaymentResponse;
import com.inyoung.ticketing.admin.dto.AdminUserResponse;
import com.inyoung.ticketing.admin.dto.UnsoldSeatSummaryItem;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.reservation.repository.ReservationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 서비스
 * 
 * 관리자 대시보드에 필요한 통계, 검색, 조회 기능을 제공합니다.
 * 모든 메서드는 읽기 전용 트랜잭션으로 실행됩니다.
 */
@Service
public class AdminService {
	private final UsersRepository usersRepository;
	private final PaymentRepository paymentRepository;
	private final ReservationRepository reservationRepository;
	private final ConcertRepository concertRepository;
	private final SeatRepository seatRepository;

	public AdminService(
		UsersRepository usersRepository,
		PaymentRepository paymentRepository,
		ReservationRepository reservationRepository,
		ConcertRepository concertRepository,
		SeatRepository seatRepository
	) {
		this.usersRepository = usersRepository;
		this.paymentRepository = paymentRepository;
		this.reservationRepository = reservationRepository;
		this.concertRepository = concertRepository;
		this.seatRepository = seatRepository;
	}

	/**
	 * 통계 응답 DTO
	 */
	public static class StatisticsResponse {
		private Long total;

		public StatisticsResponse(Long total) {
			this.total = total;
		}

		public Long getTotal() {
			return total;
		}
	}

	/**
	 * 결제 통계 응답 DTO
	 */
	public static class PaymentStatisticsResponse {
		private Long today;
		private Long totalRevenue;

		public PaymentStatisticsResponse(Long today, Long totalRevenue) {
			this.today = today;
			this.totalRevenue = totalRevenue;
		}

		public Long getToday() {
			return today;
		}

		public Long getTotalRevenue() {
			return totalRevenue;
		}
	}

	/**
	 * 사용자 통계 조회
	 * 
	 * @return 총 사용자 수
	 */
	@Transactional(readOnly = true)
	public StatisticsResponse getUserStatistics() {
		long count = usersRepository.count();
		return new StatisticsResponse(count);
	}

	/**
	 * 예약 통계 조회
	 * 
	 * @return 총 예약 수
	 */
	@Transactional(readOnly = true)
	public StatisticsResponse getReservationStatistics() {
		long count = reservationRepository.count();
		return new StatisticsResponse(count);
	}

	/**
	 * 결제 통계 조회
	 * 
	 * @return 오늘 결제 수, 총 매출액
	 */
	@Transactional(readOnly = true)
	public PaymentStatisticsResponse getPaymentStatistics() {
		// 오늘의 시작과 끝 시간
		ZoneId seoulZone = ZoneId.of("Asia/Seoul");
		LocalDate today = LocalDate.now(seoulZone);
		OffsetDateTime todayStart = today.atStartOfDay(seoulZone).toOffsetDateTime();
		OffsetDateTime todayEnd = today.atTime(23, 59, 59).atZone(seoulZone).toOffsetDateTime();

		// 오늘의 완료된 결제 조회
		List<Payment> todayPayments = paymentRepository.findByStatusAndCompletedAtBetween(
			PaymentStatus.COMPLETED,
			todayStart,
			todayEnd
		);

		long todayCount = todayPayments.size();

		// 전체 결제액 조회
		long totalRevenue = paymentRepository.sumAllAmounts();

		return new PaymentStatisticsResponse(todayCount, totalRevenue);
	}

	/**
	 * 결제 내역 조회 (검색 지원)
	 * 
	 * @param search 사용자명 또는 결제 키로 검색
	 * @param pageable 페이징 정보
	 * @return 결제 목록
	 */
	@Transactional(readOnly = true)
	public List<AdminPaymentResponse> getPayments(String search, PageRequest pageable) {
		List<Payment> payments;

		if (search != null && !search.isBlank()) {
			payments = paymentRepository.findByStatusAndUserIdContainsIgnoreCaseOrPaymentKeyContainsIgnoreCase(
				PaymentStatus.COMPLETED,
				search,
				search,
				pageable
			).getContent();
		} else {
			payments = paymentRepository.findByStatusOrderByCompletedAtDesc(
				PaymentStatus.COMPLETED,
				pageable
			).getContent();
		}

		return payments.stream()
			.map(payment -> {
				Users user = usersRepository.findById(Long.parseLong(payment.getUserId()))
					.orElse(null);
				String username = user != null ? user.getUsername() : "Unknown";
				String completedAt = payment.getCompletedAt() != null 
					? payment.getCompletedAt().toString() 
					: "-";
				return new AdminPaymentResponse(
					payment.getPaymentKey(),
					username,
					payment.getAmount(),
					payment.getStatus().toString(),
					completedAt
				);
			})
			.collect(Collectors.toList());
	}

	/**
	 * 사용자 목록 조회 (검색 지원)
	 * 
	 * @param search 사용자명 또는 이메일로 검색
	 * @param pageable 페이징 정보
	 * @return 사용자 목록
	 */
	@Transactional(readOnly = true)
	public List<AdminUserResponse> getUsers(String search, PageRequest pageable) {
		List<Users> users;

		if (search != null && !search.isBlank()) {
			users = usersRepository.findByUsernameContainsIgnoreCaseOrEmailContainsIgnoreCase(
				search,
				search,
				pageable
			).getContent();
		} else {
			users = usersRepository.findAll(pageable).getContent();
		}

		return users.stream()
			.map(user -> new AdminUserResponse(
				user.getId(),
				user.getUsername(),
				user.getEmail(),
				user.getPhone(),
				user.getPoint(),
				user.getRole(),
				user.getCreatedAt() != null ? user.getCreatedAt().toString() : "-"
			))
			.collect(Collectors.toList());
	}

	/**
	 * 마감된 공연(concertAt 경과, 취소 제외)별 미판매 좌석 통계.
	 * from/to가 모두 null이면 기간 제한 없음. 하나라도 있으면 해당 구간(한국 시간 기준)으로 필터.
	 */
	@Transactional(readOnly = true)
	public Page<UnsoldSeatSummaryItem> getUnsoldSeatStatistics(Instant from, Instant to, Pageable pageable) {
		Instant now = Instant.now();
		Instant fromBound = from != null ? from : Instant.EPOCH;
		Instant toBound = to != null ? to : now;
		Page<Concert> ended = concertRepository.findEndedConcertsNotCancelledBetween(
			now, fromBound, toBound, ConcertStatus.CANCELLED, pageable
		);
		List<UnsoldSeatSummaryItem> items = ended.getContent().stream()
			.map(c -> {
				long totalSeats = seatRepository.countByConcertId(c.getId());
				long soldSeats = seatRepository.countByConcertIdAndStatus(c.getId(), SeatStatus.RESERVED);
				long unsoldSeats = totalSeats - soldSeats;
				return new UnsoldSeatSummaryItem(
					c.getId(),
					c.getTitle(),
					c.getVenue(),
					c.getConcertAt(),
					totalSeats,
					soldSeats,
					unsoldSeats
				);
			})
			.collect(Collectors.toList());
		return new PageImpl<>(items, ended.getPageable(), ended.getTotalElements());
	}
}
