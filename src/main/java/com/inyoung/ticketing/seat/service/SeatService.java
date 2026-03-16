package com.inyoung.ticketing.seat.service;

import java.util.List;
import java.util.Set;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.dto.SeatResponse;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.springframework.stereotype.Service;

// 좌석 조회 서비스.
// DB의 좌석 상태(AVAILABLE/RESERVED)와 Redis의 홀드 상태를 결합해
// 프론트에 AVAILABLE / HELD / RESERVED 3가지 상태로 반환한다.
@Service
public class SeatService {
	private final SeatRepository seatRepository;
	private final HoldStore holdStore;

	public SeatService(SeatRepository seatRepository, HoldStore holdStore) {
		this.seatRepository = seatRepository;
		this.holdStore = holdStore;
	}

	// DB 좌석 목록을 조회한 뒤, Redis에서 현재 홀드 중인 좌석 ID를 가져와 상태를 오버레이한다.
	public List<SeatResponse> listSeats(Long concertId) {
		List<Seat> seats = seatRepository.findByConcertId(concertId);
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();
		Set<Long> heldSeatIds = holdStore.findHeldSeatIds(seatIds);
		return seats.stream()
			.map(seat -> {
				SeatStatus status = seat.getStatus() == SeatStatus.RESERVED
					? SeatStatus.RESERVED
					: heldSeatIds.contains(seat.getId()) ? SeatStatus.HELD : SeatStatus.AVAILABLE;
				return new SeatResponse(seat, status);
			})
			.toList();
	}
}
