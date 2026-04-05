package com.inyoung.ticketing.reservation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 예약 확정 요청 DTO (내부적으로는 결제 완료 플로우에서 {@link com.inyoung.ticketing.payment.service.PaymentService} 가 조립).
 * 단일 필드라도 record 로 두면 이후 필드 추가 시에도 동일한 패턴으로 확장하기 쉽다.
 */
public record ReservationRequest(@NotBlank String holdToken) {
}
