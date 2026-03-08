package com.inyoung.ticketing.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.payment.service.PaymentService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 취소된 공연(CANCELLED)에 대한 완료 결제를 청크 단위로 환불하는 배치. 분산 락으로 다중 인스턴스 시 한 노드만 실행.
 * 주기적으로 CANCELLED 콘서트의 COMPLETED 결제를 조회해 포인트 환불·결제 취소·예약/좌석 해제를 수행한다.
 */
@Component
public class RefundForCancelledConcertScheduler {

	private static final String LOCK_KEY = "lock:batch:refund";
	private static final String BATCH_NAME = "refund";
	private static final Duration LOCK_TTL = Duration.ofSeconds(360);
	private static final Logger log = LoggerFactory.getLogger(RefundForCancelledConcertScheduler.class);

	private final ConcertRepository concertRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentService paymentService;
	private final TicketingProperties properties;
	private final LockService lockService;
	private final Timer runTimer;
	private final Counter successCounter;
	private final Counter failureCounter;

	public RefundForCancelledConcertScheduler(
		ConcertRepository concertRepository,
		PaymentRepository paymentRepository,
		PaymentService paymentService,
		TicketingProperties properties,
		LockService lockService,
		MeterRegistry registry
	) {
		this.concertRepository = concertRepository;
		this.paymentRepository = paymentRepository;
		this.paymentService = paymentService;
		this.properties = properties;
		this.lockService = lockService;
		this.runTimer = Timer.builder("ticketing_batch_run_duration_seconds")
			.tag("batch", BATCH_NAME)
			.description("Refund batch run duration")
			.register(registry);
		this.successCounter = Counter.builder("ticketing_batch_run_total")
			.tag("batch", BATCH_NAME).tag("status", "success")
			.description("Batch run success count")
			.register(registry);
		this.failureCounter = Counter.builder("ticketing_batch_run_total")
			.tag("batch", BATCH_NAME).tag("status", "failure")
			.description("Batch run failure count")
			.register(registry);
	}

	@Scheduled(fixedDelayString = "${ticketing.refund.interval-ms:300000}")
	public void refundPaymentsForCancelledConcerts() {
		Optional<String> lockToken = lockService.tryLock(LOCK_KEY, LOCK_TTL);
		if (lockToken.isEmpty()) {
			return;
		}
		Timer.Sample sample = Timer.start();
		try {
			doRefundPaymentsForCancelledConcerts();
			successCounter.increment();
		} catch (Exception e) {
			failureCounter.increment();
			log.warn("Refund batch failed", e);
		} finally {
			sample.stop(runTimer);
			lockService.unlock(LOCK_KEY, lockToken.get());
		}
	}

	private void doRefundPaymentsForCancelledConcerts() {
		List<Concert> cancelledConcerts = concertRepository.findByStatus(ConcertStatus.CANCELLED);
		if (cancelledConcerts.isEmpty()) {
			return;
		}

		int batchSize = properties.getRefund().getBatchSize();
		int totalRefunded = 0;
		int totalFailed = 0;

		for (Concert concert : cancelledConcerts) {
			Long concertId = concert.getId();
			int page = 0;
			boolean hasMore;

			do {
				var pageable = PageRequest.of(page, batchSize);
				var paymentPage = paymentRepository.findByConcertIdAndStatus(concertId, PaymentStatus.COMPLETED, pageable);
				List<Payment> chunk = paymentPage.getContent();
				hasMore = paymentPage.hasNext();

				for (Payment payment : chunk) {
					try {
						boolean done = paymentService.refundCompletedPaymentForCancelledConcert(payment.getId());
						if (done) {
							totalRefunded++;
						}
					} catch (Exception e) {
						totalFailed++;
						log.warn("Refund failed for paymentId={}, concertId={}. {}", payment.getId(), concertId, e.getMessage());
					}
				}
				page++;
			} while (hasMore);
		}

		if (totalRefunded > 0 || totalFailed > 0) {
			log.info("Refund batch finished: refunded={}, failed={}", totalRefunded, totalFailed);
		}
	}
}
