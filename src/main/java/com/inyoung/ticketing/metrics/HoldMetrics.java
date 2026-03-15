package com.inyoung.ticketing.metrics;

import com.inyoung.ticketing.hold.store.HoldStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Prometheus용 홀드 메트릭. 현재 활성 홀드 수를 Gauge로 노출.
 * 메트릭명: ticketing_holds_active_count
 */
@Component
public class HoldMetrics implements MeterBinder {
	private final HoldStore holdStore;

	public HoldMetrics(HoldStore holdStore) {
		this.holdStore = holdStore;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("ticketing_holds_active_count", holdStore, HoldStore::countActiveHolds)
			.description("Current number of active seat holds (not yet expired)")
			.register(registry);
	}
}
