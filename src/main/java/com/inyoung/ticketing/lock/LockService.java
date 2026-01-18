package com.inyoung.ticketing.lock;

import java.time.Duration;
import java.util.Optional;

// 분산 락 서비스 인터페이스
public interface LockService {
	// 락 획득 시도 후 성공하면 토큰 반환
	Optional<String> tryLock(String key, Duration ttl);

	// 토큰이 일치할 때 락 해제
	boolean unlock(String key, String token);
}
