package com.inyoung.ticketing.common.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * 모든 엔티티 공통 감사(Audit) 컬럼.
 * 생성 시각과 수정 시각을 자동으로 기록해 데이터 변경 이력의 기본 축을 제공한다.
 *
 * <p>JPA 콜백({@code @PrePersist}, {@code @PreUpdate})으로 관리하므로
 * Spring Data Auditing({@code @EnableJpaAuditing}) 없이 동작한다.</p>
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

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
