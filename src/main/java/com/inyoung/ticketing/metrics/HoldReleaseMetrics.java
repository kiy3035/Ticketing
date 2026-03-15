package com.inyoung.ticketing.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 홀드 해제 사유별 카운트. reason=confirmed|timeout|cancelled
 */
@Component
public class HoldReleaseMetrics {
	private final MeterRegistry meterRegistry;

	public HoldReleaseMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void recordReleased(String reason) {
		meterRegistry.counter("ticketing_hold_released_total", "reason", reason).increment();
	}
}
