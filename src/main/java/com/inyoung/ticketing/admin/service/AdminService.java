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
import com.inyoung.ticketing.payment.domain.PaymentMethod;
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
	 * 결제 통계 응답 DTO.
	 * totalRevenuePoint: 포인트 결제 누적, totalRevenueCard: 카드(토스) 결제 누적(원).
	 */
	public static class PaymentStatisticsResponse {
		private Long today;
		private Long totalRevenuePoint;
		private Long totalRevenueCard;

		public PaymentStatisticsResponse(Long today, Long totalRevenuePoint, Long totalRevenueCard) {
			this.today = today;
			this.totalRevenuePoint = totalRevenuePoint != null ? totalRevenuePoint : 0L;
			this.totalRevenueCard = totalRevenueCard != null ? totalRevenueCard : 0L;
		}

		public Long getToday() {
			return today;
		}

		public Long getTotalRevenuePoint() {
			return totalRevenuePoint;
		}

		public Long getTotalRevenueCard() {
			return totalRevenueCard;
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
	 * 결제 통계 조회.
	 * 오늘 완료 건수 + 수단별 누적(포인트 매출 / 카드 결제 누적). 관리자 대시보드 "포인트 매출 누적", "카드 결제 누적" 표시용.
	 */
	@Transactional(readOnly = true)
	public PaymentStatisticsResponse getPaymentStatistics() {
		ZoneId seoulZone = ZoneId.of("Asia/Seoul");
		LocalDate today = LocalDate.now(seoulZone);
		java.time.LocalDateTime todayStart = today.atStartOfDay();
		java.time.LocalDateTime todayEnd = today.atTime(23, 59, 59);

		List<Payment> todayPayments = paymentRepository.findByStatusAndCompletedAtBetween(
			PaymentStatus.COMPLETED,
			todayStart,
			todayEnd
		);
		long todayCount = todayPayments.size();

		Long totalPoint = paymentRepository.sumAmountByStatusAndPaymentMethod(PaymentMethod.POINT);
		Long totalCard = paymentRepository.sumAmountByStatusAndPaymentMethod(PaymentMethod.CARD);

		return new PaymentStatisticsResponse(todayCount, totalPoint, totalCard);
	}

	/**
	 * 결제 내역 조회 (COMPLETED 만). 검색 시 사용자명 또는 결제 키로 필터.
	 * paymentMethod 포함해 반환 → 관리자 화면에서 "포인트 N포인트" / "카드 N원" 구분 표시.
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
				/* userId 는 로그인 아이디(username). findByUsername 으로 사용자명 조회 */
				Users user = usersRepository.findByUsername(payment.getUserId()).orElse(null);
				String username = user != null ? user.getUsername() : payment.getUserId();
				String completedAt = payment.getCompletedAt() != null 
					? payment.getCompletedAt().toString() 
					: "-";
				return new AdminPaymentResponse(
					payment.getPaymentKey(),
					username,
					payment.getAmount(),
					payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : "POINT",
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
