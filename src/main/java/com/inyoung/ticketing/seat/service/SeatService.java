package com.inyoung.ticketing.seat.service;

import java.util.List;
import java.util.Set;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.dto.SeatResponse;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.springframework.stereotype.Service;

// 좌석 조회 서비스
@Service
public class SeatService {
	private final SeatRepository seatRepository;
	private final HoldStore holdStore;

	// 리포지토리 주입
	public SeatService(SeatRepository seatRepository, HoldStore holdStore) {
		this.seatRepository = seatRepository;
		this.holdStore = holdStore;
	}

	// 콘서트별 좌석 목록과 Redis 홀드 상태를 결합
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
