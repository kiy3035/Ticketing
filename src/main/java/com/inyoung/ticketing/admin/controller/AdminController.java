package com.inyoung.ticketing.admin.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import com.inyoung.ticketing.admin.dto.AdminPaymentResponse;
import com.inyoung.ticketing.admin.dto.AdminUserResponse;
import com.inyoung.ticketing.admin.dto.UnsoldSeatSummaryItem;
import com.inyoung.ticketing.admin.service.AdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 API 엔드포인트
 * 
 * ADMIN 권한 사용자만 접근 가능한 통계, 결제, 사용자, 공연 관리 API를 제공합니다.
 * 모든 엔드포인트는 @PreAuthorize로 ADMIN 권한을 검증합니다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
	private final AdminService adminService;

	public AdminController(AdminService adminService) {
		this.adminService = adminService;
	}

	/**
	 * 사용자 통계 조회
	 * 
	 * @return 총 사용자 수
	 */
	@GetMapping("/statistics/users")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<AdminService.StatisticsResponse> getUserStatistics() {
		return ResponseEntity.ok(adminService.getUserStatistics());
	}

	/**
	 * 예약 통계 조회
	 * 
	 * @return 총 예약 수
	 */
	@GetMapping("/statistics/reservations")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<AdminService.StatisticsResponse> getReservationStatistics() {
		return ResponseEntity.ok(adminService.getReservationStatistics());
	}

	/**
	 * 결제 통계 조회
	 * 
	 * @return 오늘 결제 수, 총 매출액
	 */
	@GetMapping("/statistics/payments")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<AdminService.PaymentStatisticsResponse> getPaymentStatistics() {
		return ResponseEntity.ok(adminService.getPaymentStatistics());
	}

	/**
	 * 마감된 공연별 미판매 좌석 통계 (공연 일시 경과, 취소 제외).
	 * from, to는 선택(YYYY-MM-DD). 지정 시 해당 기간(한국 시간) 공연만 조회.
	 *
	 * @param from 공연일 시작 (포함), 예: 2026-01-01
	 * @param to 공연일 끝 (포함), 예: 2026-01-31
	 * @param page 페이지 번호 (기본값: 0)
	 * @param size 페이지 크기 (기본값: 20)
	 * @return 공연별 totalSeats, soldSeats, unsoldSeats
	 */
	@GetMapping("/statistics/unsold-seats")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Page<UnsoldSeatSummaryItem>> getUnsoldSeatStatistics(
		@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
		@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		ZoneId seoul = ZoneId.of("Asia/Seoul");
		Instant fromInstant = from != null ? from.atStartOfDay(seoul).toInstant() : null;
		Instant toInstant = to != null ? to.plusDays(1).atStartOfDay(seoul).toInstant() : null;
		Pageable pageable = PageRequest.of(page, size);
		return ResponseEntity.ok(adminService.getUnsoldSeatStatistics(fromInstant, toInstant, pageable));
	}

	/**
	 * 최근 결제 내역 조회 (with 검색)
	 * 
	 * @param search 사용자명 또는 예약번호로 검색
	 * @param page 페이지 번호 (기본값: 0)
	 * @param size 페이지 크기 (기본값: 20)
	 * @return 결제 내역 목록
	 */
	@GetMapping("/payments")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<List<AdminPaymentResponse>> getPayments(
		@RequestParam(required = false) String search,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return ResponseEntity.ok(adminService.getPayments(search, PageRequest.of(page, size)));
	}

	/**
	 * 사용자 목록 조회 (with 검색)
	 * 
	 * @param search 사용자명 또는 이메일로 검색
	 * @param page 페이지 번호 (기본값: 0)
	 * @param size 페이지 크기 (기본값: 20)
	 * @return 사용자 목록
	 */
	@GetMapping("/users")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<List<AdminUserResponse>> getUsers(
		@RequestParam(required = false) String search,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return ResponseEntity.ok(adminService.getUsers(search, PageRequest.of(page, size)));
	}
}
