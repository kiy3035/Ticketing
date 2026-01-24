package com.inyoung.ticketing.hold;

// 만료 처리용 홀드 페이로드
public record HoldPayload(HoldInfo info, String payload) {
}
