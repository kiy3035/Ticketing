package com.inyoung.ticketing.outbox;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ════════════════════════════════════════════════════════════════
 * [KafkaOutboxRepository]
 *
 * ■ findByStatusOrderByIdAsc
 *   Outbox 스케줄러가 PENDING 상태의 행을 오래된 순서(id ASC)로 가져온다.
 *
 *   왜 id ASC(오름차순)인가?
 *   id는 AUTO_INCREMENT라 먼저 INSERT된 행일수록 id가 작다.
 *   오래된 이벤트부터 처리해 이벤트 순서를 최대한 보장하기 위함.
 *   (Kafka 파티션 키로 순서를 보장하지만, 발행 자체는 오래된 것부터 시도)
 *
 *   왜 Pageable을 받나?
 *   PENDING 행이 대량으로 쌓였을 때 한 번에 다 가져오면 메모리 부족 위험.
 *   스케줄러가 Pageable.ofSize(배치크기)를 전달해 한 번에 N건씩 처리.
 *   배치 크기는 application.properties에서 외부화 (하드코딩 금지).
 *
 *   단점:
 *   스케줄러가 여러 인스턴스에서 동시에 실행되면 같은 행을 중복으로 가져갈 수 있다.
 *   → SELECT FOR UPDATE 또는 상태를 PROCESSING으로 먼저 변경하는 방식으로 해결 가능.
 *   → 현재는 단일 앱 서버로 운영 중이라 문제없음.
 *      스케일아웃 시 이 부분은 반드시 보완 필요.
 * ════════════════════════════════════════════════════════════════
 */
public interface KafkaOutboxRepository extends JpaRepository<KafkaOutbox, Long> {

	List<KafkaOutbox> findByStatusOrderByIdAsc(KafkaOutboxStatus status, Pageable pageable);
}
