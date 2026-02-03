package com.inyoung.ticketing.payment.controller;

import com.inyoung.ticketing.payment.dto.PaymentRequest;
import com.inyoung.ticketing.payment.dto.PaymentResponse;
import com.inyoung.ticketing.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Mock 결제 API
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	// 결제 요청 생성 (READY)
	@PostMapping("/request")
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse request(Authentication authentication, @Valid @RequestBody PaymentRequest request) {
		return paymentService.requestPayment(request, authentication.getName());
	}

	// 결제 승인 (APPROVED)
	@PostMapping("/{paymentKey}/approve")
	public PaymentResponse approve(Authentication authentication, @PathVariable String paymentKey) {
		return paymentService.approvePayment(paymentKey, authentication.getName());
	}

	// 결제 완료 (COMPLETED)
	@PostMapping("/{paymentKey}/complete")
	public PaymentResponse complete(Authentication authentication, @PathVariable String paymentKey) {
		return paymentService.completePayment(paymentKey, authentication.getName());
	}

	// 결제 취소 (CANCELED)
	@PostMapping("/{paymentKey}/cancel")
	public PaymentResponse cancel(Authentication authentication, @PathVariable String paymentKey) {
		return paymentService.cancelPayment(paymentKey, authentication.getName());
	}

	// 결제 조회
	@GetMapping("/{paymentKey}")
	public PaymentResponse get(Authentication authentication, @PathVariable String paymentKey) {
		return paymentService.getPayment(paymentKey, authentication.getName());
	}
}
