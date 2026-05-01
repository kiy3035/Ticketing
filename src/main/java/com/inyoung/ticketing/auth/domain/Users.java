package com.inyoung.ticketing.auth.domain;

import com.inyoung.ticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * ════════════════════════════════════════════════════════════════
 * [Users 엔티티 — 로컬 회원가입 사용자]
 *
 * ■ 테이블명을 "users"로 한 이유
 *   SQL 예약어 "USER"와 충돌을 피하기 위해 "users"로 명시.
 *   일부 DB에서 USER는 시스템 예약어라 테이블명으로 쓰면 오류가 난다.
 *
 * ■ @UniqueConstraint(columnNames = {"username"})
 *   username(아이디)은 중복 불가. 회원가입 시 이미 있는 아이디를 막기 위해
 *   DB 레벨 유니크 제약을 건다.
 *   @Column(unique = true)로도 선언 가능하지만, uniqueConstraints로 쓰면
 *   제약 이름을 지정할 수 있어 오류 메시지 추적이 쉽다.
 *
 * ■ pw 컬럼명 명시 (@Column(name = "pw"))
 *   필드명 "pw"는 짧아서 그대로 쓰지만, 필드명과 컬럼명을 항상 명시하는 것이
 *   JPA 컬럼명 자동 변환 규칙(카멜→스네이크) 혼동을 막는다.
 *   예: passwordHash → password_hash 자동 변환되는데, 실제 DDL과 다르면 오류.
 *
 * ■ point 필드
 *   결제 시 포인트를 차감하는 로직에서 동시 요청이 들어오면 잔액이 음수가 될 수 있다.
 *   이 문제는 UsersRepository.findWithLockByUsername()에서
 *   @Lock(PESSIMISTIC_WRITE)로 해결한다 (Repository 주석 참조).
 * ════════════════════════════════════════════════════════════════
 */
@Entity
@Table(
	name = "users",
	uniqueConstraints = @UniqueConstraint(columnNames = { "username" })
)
public class Users extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String username;

	@Column(name = "pw", nullable = false, length = 120)
	private String pw;

	@Column(nullable = false, length = 100)
	private String email;

	/** 비어 있을 수 있음. SMS 알림 시 번호 없으면 이메일 등으로 분기 */
	@Column(length = 20)
	private String phone;

	@Column(nullable = false, length = 20)
	private String notiType = "sms";

	@Column(nullable = false, length = 20)
	private String role = "USER";  // ADMIN 또는 USER

	/**
	 * 포인트 잔액.
	 * 기본값 0L. 결제 시 차감, 충전 시 증가.
	 * 동시 차감 방지는 Repository의 비관적 락(PESSIMISTIC_WRITE)으로 처리.
	 */
	@Column(name = "point", nullable = false)
	private Long point = 0L;

	// 식별자
	public Long getId() {
		return id;
	}

	// 사용자 아이디
	public String getUsername() {
		return username;
	}

	// 사용자 아이디 설정
	public void setUsername(String username) {
		this.username = username;
	}

	// 비밀번호 해시
	public String getPw() {
		return pw;
	}

	// 비밀번호 해시 설정
	public void setPw(String pw) {
		this.pw = pw;
	}

	// 이메일
	public String getEmail() {
		return email;
	}

	// 이메일 설정
	public void setEmail(String email) {
		this.email = email;
	}

	// 휴대폰번호
	public String getPhone() {
		return phone;
	}

	// 휴대폰번호 설정
	public void setPhone(String phone) {
		this.phone = phone;
	}

	// 알림 방식
	public String getNotiType() {
		return notiType;
	}

	// 알림 방식 설정
	public void setNotiType(String notiType) {
		this.notiType = notiType;
	}

	// 포인트
	public Long getPoint() {
		return point;
	}

	// 포인트 설정
	public void setPoint(Long point) {
		this.point = point;
	}

	// 역할 (ADMIN 또는 USER)
	public String getRole() {
		return role;
	}

	// 역할 설정
	public void setRole(String role) {
		this.role = role;
	}
}
