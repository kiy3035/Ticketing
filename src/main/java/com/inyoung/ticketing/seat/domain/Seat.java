package com.inyoung.ticketing.seat.domain;

import com.inyoung.ticketing.common.domain.BaseEntity;
import com.inyoung.ticketing.concert.domain.Concert;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * ════════════════════════════════════════════════════════════════
 * [Seat 엔티티 — 콘서트 내 좌석]
 *
 * ■ @Table의 uniqueConstraints
 *   DB 레벨 복합 유니크 제약 조건.
 *
 *   왜 쓰나?
 *   같은 콘서트에서 동일 구역(section) + 동일 좌석번호(seat_no)가 두 번 등록되면
 *   데이터 정합성이 깨진다. 애플리케이션 레벨 검증만으로는 동시 요청 시 뚫릴 수 있다.
 *   DB 유니크 제약은 트랜잭션 커밋 시점에 강제되므로 가장 확실한 보호막이다.
 *
 *   효과:
 *   - INSERT 시 DB가 자동으로 중복 차단 → DataIntegrityViolationException 발생
 *   - 인덱스도 자동 생성 → (concert_id, section, seat_no) 조합 조회 속도 향상
 *
 *   단점:
 *   - 제약 위반 예외를 서비스에서 catch해 사용자 친화적 메시지로 변환해야 함.
 *   - 단일 컬럼 유니크(@Column(unique=true))와 달리 복합 유니크는
 *     반드시 @Table 레벨에서 선언해야 한다.
 * ════════════════════════════════════════════════════════════════
 */
@Entity
@Table(
	name = "seat",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "concert_id", "section", "seat_no" })
	}
)
public class Seat extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * 소속 콘서트 연관관계.
	 *
	 * ■ optional = false
	 *   - 좌석은 반드시 콘서트에 속해야 한다는 의미 (null 허용 안 함).
	 *   - JPA가 쿼리를 생성할 때 INNER JOIN을 사용하게 된다.
	 *     optional = true(기본값)이면 LEFT OUTER JOIN을 사용 — 불필요하게 더 무거움.
	 *   - 효과: 더 가벼운 JOIN, 모델 의도 명확화.
	 *   - 단점: 없음. 실제로 concert 없는 seat은 존재할 수 없으므로 정확한 모델링.
	 *
	 * ■ FetchType.LAZY
	 *   - 좌석 목록 조회 시 각 좌석의 콘서트 정보까지 항상 가져오면 낭비.
	 *     예: 100개 좌석 조회 시 EAGER면 콘서트 JOIN 100번 발생 (N+1).
	 *   - LAZY로 설정해 좌석만 필요할 때는 concert를 로딩하지 않음.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "concert_id", nullable = false)
	private Concert concert;

	@Column(nullable = false, length = 50)
	private String section;

	@Column(name = "seat_no", nullable = false, length = 20)
	private String seatNo;

	/**
	 * 가격.
	 * Long 타입 사용 이유: int의 최대값은 약 21억 원인데 가격이 크거나 적립 포인트 등이
	 * 합산되는 경우를 고려해 Long으로 통일.
	 */
	@Column(nullable = false)
	private Long price;

	/**
	 * 좌석 상태.
	 * AVAILABLE → HELD(Redis 홀드) → RESERVED(예매 완료).
	 * @Enumerated(EnumType.STRING): 순서 변경에 안전하게 문자열로 저장.
	 * (자세한 설명은 Concert.java의 status 필드 참조)
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SeatStatus status = SeatStatus.AVAILABLE;

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

	// 구역
	public String getSection() {
		return section;
	}

	// 구역 설정
	public void setSection(String section) {
		this.section = section;
	}

	// 좌석 번호
	public String getSeatNo() {
		return seatNo;
	}

	// 좌석 번호 설정
	public void setSeatNo(String seatNo) {
		this.seatNo = seatNo;
	}

	// 가격
	public Long getPrice() {
		return price;
	}

	// 가격 설정
	public void setPrice(Long price) {
		this.price = price;
	}

	// 좌석 상태
	public SeatStatus getStatus() {
		return status;
	}

	// 좌석 상태 설정
	public void setStatus(SeatStatus status) {
		this.status = status;
	}
}
