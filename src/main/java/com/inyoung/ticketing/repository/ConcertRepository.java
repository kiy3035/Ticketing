package com.inyoung.ticketing.repository;

import com.inyoung.ticketing.domain.Concert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertRepository extends JpaRepository<Concert, Long> {
}
