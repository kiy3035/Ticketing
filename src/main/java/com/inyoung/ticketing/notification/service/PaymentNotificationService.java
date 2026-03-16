package com.inyoung.ticketing.notification.service;

import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// 결제 완료 알림 라우터.
// 사용자의 notiType(email/sms)에 따라 EmailService 또는 SmsService 로 분기한다.
// 알림 실패가 결제 프로세스를 중단시키지 않도록 예외를 catch 해 로깅만 수행한다.
@Service
public class PaymentNotificationService {
	private static final Logger logger = LoggerFactory.getLogger(PaymentNotificationService.class);
	private final EmailService emailService;
	private final SmsService smsService;
	private final UsersRepository usersRepository;
	private final ConcertRepository concertRepository;

	public PaymentNotificationService(
		EmailService emailService,
		SmsService smsService,
		UsersRepository usersRepository,
		ConcertRepository concertRepository
	) {
		this.emailService = emailService;
		this.smsService = smsService;
		this.usersRepository = usersRepository;
		this.concertRepository = concertRepository;
	}

	public void notifyPaymentComplete(String userId, Long concertId, long amount) {
		try {
			Users user = usersRepository.findByUsername(userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

			Concert concert = concertRepository.findById(concertId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Concert not found"));

			String notiType = user.getNotiType();
			logger.info("Sending {} notification to user: {} for concert: {}", notiType, userId, concertId);

			if ("email".equals(notiType)) {
				emailService.sendPaymentCompleteEmail(
					user.getEmail(),
					user.getUsername(),
					concert.getTitle(),
					amount
				);
			} else if ("sms".equals(notiType)) {
				smsService.sendPaymentCompleteSms(
					user.getPhone(),
					user.getUsername(),
					concert.getTitle(),
					amount
				);
			} else {
				logger.warn("Unknown notification type: {}", notiType);
			}
		} catch (Exception e) {
			logger.error("Error sending payment notification", e);
			// 알림 전송 실패가 결제 프로세스를 중단하지 않도록 처리
		}
	}
}
