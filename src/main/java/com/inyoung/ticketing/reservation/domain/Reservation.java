package com.inyoung.ticketing.reservation.domain;

import java.time.LocalDateTime;
import com.inyoung.ticketing.common.domain.BaseEntity;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.seat.domain.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * ════════════════════════════════════════════════════════════════
 * [Reservation 엔티티 — 좌석 예약 확정]
 *
 * ■ 연관관계 설계 의도
 *   Reservation은 Concert와 Seat 두 엔티티를 모두 참조한다.
 *   Seat만 참조해도 Concert 정보를 얻을 수 있지만 (seat.getConcert()),
 *   concert_id를 직접 저장하는 이유:
 *   - 예약 목록을 "콘서트별로 조회"하는 쿼리가 자주 있음.
 *     concert_id 직접 참조 시 인덱스를 곧바로 타서 빠름.
 *   - seat → concert 를 거치는 2단계 탐색보다 1단계가 더 단순하고 명시적.
 *
 * ■ userId를 String으로 (Users 엔티티 참조 없음)
 *   예약 생성/조회에서 실제 Users 객체(이메일, 전화번호 등)가 필요 없다.
 *   필요할 때만 UsersRepository에서 따로 조회하면 되므로, 불필요한 연관관계를 제거해
 *   쿼리를 단순하게 유지했다.
 *   단점: DB 레벨의 FK 제약이 없어서 없는 userId로 예약이 생성될 수 있다.
 *         참조 무결성을 애플리케이션 레이어에서 보장해야 한다.
 * ════════════════════════════════════════════════════════════════
 */
@Entity
@Table(name = "reservation")
public class Reservation extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * 소속 콘서트.
	 * LAZY: 예약 조회 시 콘서트 정보가 항상 필요하지 않음.
	 * optional = false: 예약은 반드시 콘서트에 속함 → INNER JOIN 사용 → 쿼리 성능 소폭 향상.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "concert_id", nullable = false)
	private Concert concert;

	/**
	 * 예약된 좌석.
	 * LAZY: 예약 조회 시 좌석 상세 정보가 항상 필요하지 않음.
	 * optional = false: 예약은 반드시 좌석을 가짐.
	 *
	 * ※ N+1 주의 포인트:
	 *   예약 목록을 반복문으로 돌며 getSeat().getPrice() 같이 접근하면
	 *   예약 수만큼 Seat SELECT가 추가 발생한다.
	 *   이를 방지하려면 JPQL에서 JOIN FETCH r.seat 로 한 번에 가져와야 한다.
	 *   (현재 코드에서 이 부분은 서비스 레이어에서 DTO 변환 시 주의 필요)
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seat_id", nullable = false)
	private Seat seat;

	@Column(nullable = false, length = 64)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReservationStatus status = ReservationStatus.CONFIRMED;

	/**
	 * 예약 확정 시각.
	 *
	 * ■ @PrePersist로 자동 설정하는 이유
	 *   BaseEntity의 createdAt과 의미가 겹치지만, createdAt은 BaseEntity 공통이고
	 *   reservedAt은 "비즈니스적 예약 확정 시각"이라는 별도 의미를 가진다.
	 *   예: 나중에 예약 이력에 "예약 일시: 2024-03-01 14:30:00" 형태로 노출할 때
	 *       명시적인 필드가 있는 것이 더 명확하다.
	 *
	 * ■ withNano(0)
	 *   MySQL DATETIME 기본 정밀도는 초 단위. 나노초를 미리 제거해
	 *   DB에서 읽어온 값과 객체 값의 불일치를 방지.
	 */
	@Column(nullable = false)
	private LocalDateTime reservedAt;

	@PrePersist
	void prePersist() {
		this.reservedAt = LocalDateTime.now().withNano(0);
	}

	// 식별자
	public Long getId() {
		return id;
	}

	// 소속 콘서트
	public Concert getConcert() {
		return concert;
	}

	// 소속 콘서트 설정
	public void setConcert(Concert concert) {
		this.concert = concert;
	}

	// 예약 좌석
	public Seat getSeat() {
		return seat;
	}

	// 예약 좌석 설정
	public void setSeat(Seat seat) {
		this.seat = seat;
	}

	// 예약 사용자
	public String getUserId() {
		return userId;
	}

	// 예약 사용자 설정
	public void setUserId(String userId) {
		this.userId = userId;
	}

	// 예약 상태
	public ReservationStatus getStatus() {
		return status;
	}

	// 예약 상태 설정
	public void setStatus(ReservationStatus status) {
		this.status = status;
	}

	public LocalDateTime getReservedAt() {
		return reservedAt;
	}
}
