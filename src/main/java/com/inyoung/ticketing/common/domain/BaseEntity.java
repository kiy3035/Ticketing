package com.inyoung.ticketing.common.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * ════════════════════════════════════════════════════════════════
 * [BaseEntity — 공통 감사(Audit) 컬럼]
 *
 * ■ 왜 쓰나?
 *   모든 엔티티에 createdAt, updatedAt이 반복되면 코드 중복이 생긴다.
 *   @MappedSuperclass로 이 클래스를 상속하면 자식 엔티티 테이블에
 *   두 컬럼이 자동으로 포함된다. 즉 "공통 컬럼을 한 곳에서 관리"하는 패턴.
 *
 * ■ @MappedSuperclass
 *   - 이 클래스 자체는 DB 테이블을 만들지 않는다.
 *   - 자식 엔티티가 상속받으면 자식 테이블에 컬럼이 포함된다.
 *   - @Entity가 아니므로 직접 조회(Repository) 불가.
 *   - 효과: 공통 컬럼 관리 일원화 → 수정 포인트 한 곳
 *   - 단점: Java는 단일 상속이라 이미 다른 클래스를 상속 중이면 사용 불가.
 *           (@Embeddable + @Embedded로 구성으로 우회 가능)
 *
 * ■ @PrePersist / @PreUpdate (JPA 라이프사이클 콜백)
 *   - @PrePersist: EntityManager.persist() 직전에 호출됨 → INSERT 전
 *   - @PreUpdate:  EntityManager.merge() 또는 dirty checking 직전 → UPDATE 전
 *   - Spring Data의 @EnableJpaAuditing + @CreatedDate/@LastModifiedDate 대신
 *     순수 JPA 콜백만으로 처리한 이유:
 *     Spring Auditing은 SecurityContext에서 사용자 정보까지 자동으로 넣을 수 있어
 *     더 강력하지만, 단순 시각 기록만 필요한 이 프로젝트에서는 의존성을 줄이고자 JPA 콜백 선택.
 *   - 효과: 코드에서 직접 시각을 set하지 않아도 자동 기록됨
 *   - 단점: 테스트에서 시간을 조작하려면 Clock을 주입하는 방식이 더 유연함.
 *           (현재는 LocalDateTime.now() 직접 호출이라 시간 고정 테스트가 어려움)
 *
 * ■ withNano(0)
 *   - MySQL DATETIME은 초 단위까지만 저장(기본), 나노초는 버려진다.
 *   - DB에서 읽어온 값과 애플리케이션 객체의 값이 나노초 차이로 달라지는
 *     미묘한 버그를 방지하기 위해 미리 잘라서 저장.
 * ════════════════════════════════════════════════════════════════
 */
@MappedSuperclass
public abstract class BaseEntity {

	/**
	 * 레코드 최초 생성 시각.
	 * updatable = false: INSERT 후 이 컬럼은 UPDATE 대상에서 제외됨.
	 * → 한 번 기록된 생성 시각은 절대 바뀌지 않도록 DB 레벨에서 강제.
	 */
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * 레코드 최종 수정 시각.
	 * @PreUpdate 콜백으로 엔티티가 변경될 때마다 자동 갱신.
	 */
	@Column(nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onPrePersist() {
		LocalDateTime now = LocalDateTime.now().withNano(0);
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	protected void onPreUpdate() {
		this.updatedAt = LocalDateTime.now().withNano(0);
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}
}
