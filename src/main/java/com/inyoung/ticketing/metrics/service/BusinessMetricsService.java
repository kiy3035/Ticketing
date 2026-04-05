package com.inyoung.ticketing.metrics.service;

import com.inyoung.ticketing.hold.store.HoldStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * 비즈니스 커스텀 메트릭을 Prometheus에 노출하는 서비스.
 *
 * <p>노출 메트릭:
 * <ul>
 *   <li>{@code ticketing_active_holds}: 현재 활성 홀드 수 (Gauge)</li>
 *   <li>{@code ticketing_hold_created_total}: 홀드 생성 총 수 (Counter, HoldService에서 등록)</li>
 *   <li>{@code ticketing_hold_released_total}: 홀드 해제 총 수 (Counter, HoldReleaseMetrics에서 등록)</li>
 *   <li>{@code ticketing_lock_acquire_failures_total}: 락 획득 실패 수 (Counter, HoldService/ReservationService에서 등록)</li>
 *   <li>{@code ticketing_hold_conflict_total}: Redis 홀드 경합으로 생성 거절 (Counter, HoldService)</li>
 *   <li>{@code ticketing_payment_completed_total}: 결제 완료 수 (Counter, PaymentService에서 등록)</li>
 *   <li>{@code ticketing_payment_complete_duration_seconds}: 결제 완료 소요 시간 (Timer, PaymentService에서 등록)</li>
 *   <li>{@code ticketing_reservation_confirmed_total}: 예약 확정 수 (Counter, ReservationService에서 등록)</li>
 * </ul>
 *
 * <p>Grafana 대시보드에서 이 메트릭들을 조합해:
 * <ul>
 *   <li>홀드 시도 대비 성공 = created / (created + lock_failures + hold_conflict)</li>
 *   <li>결제 완료율 = completed / total_payment_requests</li>
 *   <li>평균 결제 소요 시간 = payment_complete_duration_seconds</li>
 * </ul>
 * 등의 비즈니스 대시보드를 구성할 수 있다.
 */
@Service
public class BusinessMetricsService {

	private final MeterRegistry meterRegistry;
	private final HoldStore holdStore;

	public BusinessMetricsService(MeterRegistry meterRegistry, HoldStore holdStore) {
		this.meterRegistry = meterRegistry;
		this.holdStore = holdStore;
	}

	@PostConstruct
	public void registerGauges() {
		Gauge.builder("ticketing_active_holds", holdStore::countActiveHolds)
			.description("Number of currently active (non-expired) seat holds")
			.register(meterRegistry);
	}

	/** 결제 시도 카운터 (성공/실패 태그) */
	public void recordPaymentAttempt(String result) {
		meterRegistry.counter("ticketing_payment_attempts_total", "result", result).increment();
	}

	/** 대기열 평균 대기 시간 기록 */
	public Timer.Sample startQueueWaitTimer() {
		return Timer.start(meterRegistry);
	}

	public void stopQueueWaitTimer(Timer.Sample sample) {
		sample.stop(Timer.builder("ticketing_queue_wait_duration_seconds")
			.description("Time spent waiting in queue before entry allowed")
			.register(meterRegistry));
	}
}
