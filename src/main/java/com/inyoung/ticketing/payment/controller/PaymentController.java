package com.inyoung.ticketing.payment.controller;

import com.inyoung.ticketing.common.idempotency.Idempotent;
import com.inyoung.ticketing.common.ratelimit.RateLimit;
import com.inyoung.ticketing.payment.dto.CardApproveRequest;
import com.inyoung.ticketing.payment.dto.PaymentRequest;
import com.inyoung.ticketing.payment.dto.PaymentResponse;
import com.inyoung.ticketing.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * <p>멱등성: {@code Idempotency-Key} 헤더로 네트워크 재시도 시 중복 결제를 방지한다.</p>
 * <p>Rate Limit: 사용자당 초당 5회로 결제 API 남용을 방지한다.</p>
 */
@Tag(name = "결제", description = "결제 요청/승인/완료/취소 API")
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

	@Operation(summary = "결제 요청", description = "READY 상태 Payment 생성. CARD 시 orderId 반환")
	@Idempotent(ttlSeconds = 86400)
	@RateLimit(maxRequests = 5, windowSeconds = 1)
	@PostMapping("/request")
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse request(Authentication authentication, @Valid @RequestBody PaymentRequest request) {
		return paymentService.requestPayment(request, authentication.getName());
	}

	@Operation(summary = "결제 승인", description = "POINT: 포인트 차감, CARD: 토스 confirm 호출 후 APPROVED")
	@Idempotent(ttlSeconds = 86400)
	@RateLimit(maxRequests = 5, windowSeconds = 1)
	@PostMapping("/{paymentKey}/approve")
	public PaymentResponse approve(
		Authentication authentication,
		@PathVariable String paymentKey,
		@RequestBody(required = false) CardApproveRequest cardApproveRequest
	) {
		return paymentService.approvePaymentWithOption(paymentKey, authentication.getName(), cardApproveRequest);
	}

	@Operation(summary = "결제 완료", description = "예약 확정 후 COMPLETED 전환. 실패 시 보상 트랜잭션 실행")
	@Idempotent(ttlSeconds = 86400)
	@RateLimit(maxRequests = 5, windowSeconds = 1)
	@PostMapping("/{paymentKey}/complete")
	public PaymentResponse complete(Authentication authentication, @PathVariable String paymentKey) {
		return paymentService.completePayment(paymentKey, authentication.getName());
	}

	@Operation(summary = "결제 취소")
	@RateLimit(maxRequests = 5, windowSeconds = 1)
	@PostMapping("/{paymentKey}/cancel")
	public PaymentResponse cancel(Authentication authentication, @PathVariable String paymentKey) {
		return paymentService.cancelPayment(paymentKey, authentication.getName());
	}

	@Operation(summary = "결제 조회")
	@GetMapping("/{paymentKey}")
	public PaymentResponse get(Authentication authentication, @PathVariable String paymentKey) {
		return paymentService.getPayment(paymentKey, authentication.getName());
	}
}
