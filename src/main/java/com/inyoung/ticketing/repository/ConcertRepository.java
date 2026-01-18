package com.inyoung.ticketing.repository;

import com.inyoung.ticketing.domain.Concert;
import org.springframework.data.jpa.repository.JpaRepository;

// 콘서트 기본 CRUD 리포지토리
public interface ConcertRepository extends JpaRepository<Concert, Long> {
}
