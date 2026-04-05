package com.inyoung.ticketing.outbox;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA: 메서드 이름으로 쿼리 생성 — id 오름차순으로 오래된 PENDING 부터 처리 */
public interface KafkaOutboxRepository extends JpaRepository<KafkaOutbox, Long> {

	List<KafkaOutbox> findByStatusOrderByIdAsc(KafkaOutboxStatus status, Pageable pageable);
}
