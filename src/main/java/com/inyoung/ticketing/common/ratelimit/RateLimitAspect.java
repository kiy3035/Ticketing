package com.inyoung.ticketing.common.ratelimit;

import com.inyoung.ticketing.common.exception.BusinessException;
import com.inyoung.ticketing.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link RateLimit} 어노테이션이 붙은 메서드에 대해
 * Redis Sliding Window 기반 Rate Limiting을 적용하는 AOP Aspect.
 *
 * <p>사용자 식별: 인증된 사용자는 username, 미인증이면 클라이언트 IP를 사용한다.</p>
 */
@Aspect
@Component
public class RateLimitAspect {
	private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

	private final RateLimitService rateLimitService;

	public RateLimitAspect(RateLimitService rateLimitService) {
		this.rateLimitService = rateLimitService;
	}

	@Around("@annotation(rateLimit)")
	public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
		String identifier = resolveIdentifier();
		boolean allowed = rateLimitService.isAllowed(
			identifier,
			rateLimit.maxRequests(),
			rateLimit.windowSeconds()
		);
		if (!allowed) {
			log.warn("Rate limit exceeded for: {}", identifier);
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
		return joinPoint.proceed();
	}

	private String resolveIdentifier() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
			return "user:" + auth.getName();
		}
		ServletRequestAttributes attrs =
			(ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		if (attrs != null) {
			HttpServletRequest request = attrs.getRequest();
			String xff = request.getHeader("X-Forwarded-For");
			if (xff != null && !xff.isBlank()) {
				return "ip:" + xff.split(",")[0].trim();
			}
			return "ip:" + request.getRemoteAddr();
		}
		return "unknown";
	}
}
