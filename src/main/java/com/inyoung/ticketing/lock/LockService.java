package com.inyoung.ticketing.lock;

import java.time.Duration;
import java.util.Optional;

public interface LockService {
	Optional<String> tryLock(String key, Duration ttl);

	boolean unlock(String key, String token);
}
