package com.inyoung.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.dto.HoldRequest;
import com.inyoung.ticketing.hold.event.SeatHoldEventPublisher;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.metrics.HoldReleaseMetrics;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * HoldService 단위 테스트.
 * 좌석 홀드 생성(createHold) 시 "락 실패 → 429", "좌석 없음 → 404", "성공 시 응답 + 락 해제" 등
 * 시나리오별 동작을 검증한다. Repository·Lock·HoldStore·이벤트 퍼블리셔는 Mock으로 격리한다.
 */
@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

	@Mock
	private SeatRepository seatRepository;
	@Mock
	private ConcertRepository concertRepository;
	@Mock
	private LockService lockService;
	@Mock
	private HoldStore holdStore;
	@Mock
	private SeatHoldEventPublisher eventPublisher;
	@Mock
	private HoldReleaseMetrics holdReleaseMetrics;

	private TicketingProperties properties;
	private HoldService holdService;

	private static final Long CONCERT_ID = 1L;
	private static final Long SEAT_ID = 10L;
	private static final String USER_ID = "user1";

	@BeforeEach
	void setUp() {
		properties = new TicketingProperties();
		properties.getLock().setTtlSeconds(5);
		properties.getHold().setTtlSeconds(600);
		holdService = new HoldService(
			seatRepository,
			concertRepository,
			lockService,
			properties,
			holdStore,
			eventPublisher,
			holdReleaseMetrics,
			new SimpleMeterRegistry()
		);
	}

	/**
	 * createHold: 좌석에 대한 분산 락을 얻지 못했을 때(다른 사용자가 선점 중)
	 * - ResponseStatusException 429(Too Many Requests)가 발생하는지
	 * - 예외 메시지에 "Seat is busy"가 포함되는지
	 * 검증. 클라이언트가 재시도 또는 다른 좌석 선택 유도 메시지를 보여줄 수 있도록 한다.
	 */
	@Test
	void createHold_throws429_whenLockFails() {
		Seat seat = seat();
		Concert concert = concert();
		seat.setConcert(concert);
		when(seatRepository.findById(SEAT_ID)).thenReturn(Optional.of(seat));
		when(lockService.tryLock(any(), any())).thenReturn(Optional.empty());

		HoldRequest request = new HoldRequest(CONCERT_ID, SEAT_ID);

		assertThatThrownBy(() -> holdService.createHold(request, USER_ID))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(429))
			.hasMessageContaining("Seat is busy");
	}

	/**
	 * createHold: 요청한 seatId에 해당하는 좌석이 DB에 없을 때
	 * - ResponseStatusException 404(Not Found)가 발생하는지 검증.
	 * 잘못된 seatId 또는 이미 삭제된 좌석 요청에 대한 방어 로직을 확인한다.
	 */
	@Test
	void createHold_throws404_whenSeatNotFound() {
		when(seatRepository.findById(SEAT_ID)).thenReturn(Optional.empty());

		HoldRequest request = new HoldRequest(CONCERT_ID, SEAT_ID);

		assertThatThrownBy(() -> holdService.createHold(request, USER_ID))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
	}

	/**
	 * createHold: 락 획득 성공 후 HoldStore에 홀드가 정상 생성되었을 때
	 * - HoldResponse가 반환되고 holdToken·expiresAt이 유효한지
	 * - finally에서 lockService.unlock(lockKey, lockToken)이 호출되어 락이 해제되는지
	 * 검증. 리소스 누수(락 미해제) 방지와 성공 응답 형식을 확인한다.
	 */
	@Test
	void createHold_returnsHoldResponse_whenLockSucceedsAndHoldCreated() {
		Seat seat = seat();
		Concert concert = concert();
		seat.setConcert(concert);
		when(seatRepository.findById(SEAT_ID)).thenReturn(Optional.of(seat));
		when(lockService.tryLock(any(), any())).thenReturn(Optional.of("lock-token"));
		when(holdStore.createHold(any(), any())).thenReturn(true);

		HoldRequest request = new HoldRequest(CONCERT_ID, SEAT_ID);

		var response = holdService.createHold(request, USER_ID);

		assertThat(response).isNotNull();
		assertThat(response.holdToken()).isNotBlank();
		assertThat(response.expiresAt()).isAfter(Instant.now());
		verify(lockService).unlock(eq("lock:seat:" + SEAT_ID), eq("lock-token"));
	}

	private Seat seat() {
		Seat s = new Seat();
		s.setSection("A");
		s.setSeatNo("1");
		s.setPrice(50000L);
		s.setStatus(SeatStatus.AVAILABLE);
		// id is set via reflection for test (entity has no setId)
		try {
			var idField = Seat.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(s, SEAT_ID);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return s;
	}

	private Concert concert() {
		Concert c = new Concert();
		c.setTitle("Test Concert");
		c.setVenue("Hall");
		c.setConcertAt(Instant.now().plusSeconds(3600));
		try {
			var idField = Concert.class.getDeclaredField("id");
			idField.setAccessible(true);
			idField.set(c, CONCERT_ID);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return c;
	}
}
