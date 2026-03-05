package com.inyoung.ticketing.seller.controller;

import java.util.List;
import com.inyoung.ticketing.seller.dto.*;
import com.inyoung.ticketing.seller.service.SellerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seller")
@PreAuthorize("hasRole('SELLER')")
public class SellerController {
	private final SellerService sellerService;

	public SellerController(SellerService sellerService) {
		this.sellerService = sellerService;
	}

	@GetMapping("/concerts")
	public ResponseEntity<List<SellerConcertResponse>> getMyConcerts(Authentication auth) {
		return ResponseEntity.ok(sellerService.getMyConcerts(auth.getName()));
	}

	@PostMapping("/concerts")
	public ResponseEntity<SellerConcertResponse> createConcert(Authentication auth,
		@Valid @RequestBody SellerConcertCreateRequest request) {
		return ResponseEntity.ok(sellerService.createConcert(auth.getName(), request));
	}

	@GetMapping("/concerts/{concertId}")
	public ResponseEntity<SellerConcertResponse> getConcert(Authentication auth, @PathVariable Long concertId) {
		return ResponseEntity.ok(sellerService.getConcert(auth.getName(), concertId));
	}

	@PatchMapping("/concerts/{concertId}")
	public ResponseEntity<SellerConcertResponse> updateConcert(Authentication auth, @PathVariable Long concertId,
		@RequestBody SellerConcertUpdateRequest request) {
		return ResponseEntity.ok(sellerService.updateConcert(auth.getName(), concertId, request));
	}

	@PostMapping("/concerts/{concertId}/cancel")
	public ResponseEntity<Void> cancelConcert(Authentication auth, @PathVariable Long concertId) {
		sellerService.cancelConcert(auth.getName(), concertId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/concerts/{concertId}/seats")
	public ResponseEntity<List<SellerSeatResponse>> getSeats(Authentication auth, @PathVariable Long concertId) {
		return ResponseEntity.ok(sellerService.getSeats(auth.getName(), concertId));
	}

	@PostMapping("/concerts/{concertId}/seats")
	public ResponseEntity<List<SellerSeatResponse>> createSeats(Authentication auth, @PathVariable Long concertId,
		@Valid @RequestBody SellerSeatCreateRequest request) {
		return ResponseEntity.ok(sellerService.createSeats(auth.getName(), concertId, request));
	}

	@GetMapping("/concerts/{concertId}/reservations")
	public ResponseEntity<List<SellerReservationResponse>> getReservations(Authentication auth, @PathVariable Long concertId) {
		return ResponseEntity.ok(sellerService.getReservations(auth.getName(), concertId));
	}

	@GetMapping("/concerts/{concertId}/sales")
	public ResponseEntity<SellerSalesSummaryResponse> getSales(Authentication auth, @PathVariable Long concertId) {
		return ResponseEntity.ok(sellerService.getSales(auth.getName(), concertId));
	}
}
