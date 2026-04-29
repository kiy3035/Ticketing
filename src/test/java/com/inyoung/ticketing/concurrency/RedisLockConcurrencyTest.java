package com.inyoung.ticketing.concurrency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 분산 락 동시성 테스트.
 *
 * <p>동일 키에 대해 50개 스레드가 동시에 락을 시도할 때
 * 정확히 1개만 성공하는지 검증한다.</p>
 */
class RedisLockConcurrencyTest extends IntegrationTestBase {

	@Autowired private LockService lockService;

	@Test
	@DisplayName("50개 스레드가 동시에 동일 키 락 시도 → 1개만 성공")
	void concurrentLock_onlyOneAcquires() throws Exception {
		int threadCount = 50;
		String lockKey = "test:lock:concurrent";
		Duration ttl = Duration.ofSeconds(10);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger exceptionCount = new AtomicInteger(0);
		List<Future<?>> futures = new ArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			futures.add(executor.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();

					Optional<String> token = lockService.tryLock(lockKey, ttl);
					if (token.isPresent()) {
						successCount.incrementAndGet();
					}
				} catch (Exception e) {
					// Redis 연결 실패 시 카운트. successCount=0 이면 race condition이 아닌 인프라 문제.
					exceptionCount.incrementAndGet();
				}
			}));
		}

		readyLatch.await();
		startLatch.countDown();

		for (Future<?> future : futures) {
			future.get();
		}
		executor.shutdown();

		assertThat(successCount.get())
			.as("동시 락 시도 시 정확히 1개만 획득해야 한다 (예외 발생 스레드: %d)", exceptionCount.get())
			.isEqualTo(1);
	}
}
