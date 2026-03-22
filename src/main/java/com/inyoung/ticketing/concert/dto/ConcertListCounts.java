package com.inyoung.ticketing.concert.dto;

/** 예매 가능(공연 일시가 현재 이후) / 지난 공연(공연 일시가 현재 이전) 전체 건수 */
public record ConcertListCounts(long upcoming, long past) {}
