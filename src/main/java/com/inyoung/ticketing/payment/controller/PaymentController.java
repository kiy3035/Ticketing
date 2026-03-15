package com.inyoung.ticketing.payment.controller;

import com.inyoung.ticketing.payment.dto.CardApproveRequest;
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
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.payment.dto.TossClientKeyResponse;

/**
 * 결제 API: 포인트 결제(회원 포인트 차감) / 카드 결제(토스페이먼츠 주문서형 위젯 모의결제).
 *
 * [흐름] request(READY 생성, CARD 시 orderId 반환) → approve(POINT: 포인트 차감, CARD: 토스 confirm 호출) → complete(예약 확정).
 * 카드 결제 시 프론트는 request 후 위젯 requestPayment → successUrl 리다이렉트 후 approve(body에 토스 paymentKey/orderId/amount) 호출.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
	private final PaymentService paymentService;
	private final TicketingProperties properties;

	public PaymentController(PaymentService paymentService, TicketingProperties properties) {
		this.paymentService = paymentService;
		this.properties = properties;
	}

	/**
	 * 카드 결제 시 프론트에서 토스 주문서형 위젯 초기화에 사용할 클라이언트 키.
	 * 주문서형은 결제위젯 연동 키(test_gck_...)만 지원. 시크릿 키는 노출하지 않음.
	 */
	@GetMapping("/toss-client-key")
	public TossClientKeyResponse tossClientKey() {
		String key = properties.getToss().getClientKey();
		return new TossClientKeyResponse(key != null ? key : "");
	}

	/**
	 * 결제 요청: holdToken, paymentMethod(POINT/CARD). READY 상태 Payment 생성.
	 * CARD 시 orderId 부여 후 반환(프론트에서 widgets.requestPayment(orderId, ...) 에 사용).
	 */
	@PostMapping("/request")
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse request(Authentication authentication, @Valid @RequestBody PaymentRequest request) {
		return paymentService.requestPayment(request, authentication.getName());
	}

	/**
	 * 결제 승인.
	 * POINT: body 없음. 서버에서 보유 포인트 차감 후 APPROVED.
	 * CARD: body 에 토스 successUrl 리다이렉트 쿼리로 전달된 paymentKey, orderId, amount 필수. 토스 confirm API 호출 후 APPROVED.
	 */
	@PostMapping("/{paymentKey}/approve")
	public PaymentResponse approve(
		Authentication authentication,
		@PathVariable String paymentKey,
		@RequestBody(required = false) CardApproveRequest cardApproveRequest
	) {
		return paymentService.approvePaymentWithOption(paymentKey, authentication.getName(), cardApproveRequest);
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
