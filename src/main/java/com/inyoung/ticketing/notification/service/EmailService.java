package com.inyoung.ticketing.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

// Google SMTP(JavaMailSender) 기반 이메일 전송 서비스.
@Service
public class EmailService {
	private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
	private final JavaMailSender mailSender;

	public EmailService(JavaMailSender mailSender) {
		this.mailSender = mailSender;
	}

	public void sendPaymentCompleteEmail(String email, String username, String concertName, long amount) {
		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setTo(email);
			message.setSubject("[콘서트 예매] 결제 완료 안내");
			message.setText(buildPaymentCompleteEmailBody(username, concertName, amount));
			message.setFrom("noreply@concert-ticketing.com");

			mailSender.send(message);
			logger.info("Payment completion email sent to: {}", email);
		} catch (Exception e) {
			logger.error("Failed to send payment completion email to: {}", email, e);
			throw new RuntimeException("Failed to send email", e);
		}
	}

	private String buildPaymentCompleteEmailBody(String username, String concertName, long amount) {
		return String.format(
			"안녕하세요, %s님!\n\n" +
			"콘서트 예매가 완료되었습니다.\n\n" +
			"콘서트: %s\n" +
			"결제 금액: %,d포인트\n\n" +
			"예매 내역은 마이페이지에서 확인할 수 있습니다.\n\n" +
			"감사합니다.\n" +
			"콘서트 예매 팀",
			username, concertName, amount
		);
	}

	/** 회원가입 성공 시 환영 이메일 전송 (Gmail SMTP) */
	public void sendSignupSuccessEmail(String email, String username) {
		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setTo(email);
			message.setSubject("[콘서트 예매] 회원가입을 완료했습니다");
			message.setText(buildSignupSuccessEmailBody(username));
			message.setFrom("noreply@concert-ticketing.com");

			mailSender.send(message);
			logger.info("Signup success email sent to: {}", email);
		} catch (Exception e) {
			logger.error("Failed to send signup success email to: {}", email, e);
			throw new RuntimeException("Failed to send email", e);
		}
	}

	private String buildSignupSuccessEmailBody(String username) {
		return String.format(
			"안녕하세요, %s님!\n\n" +
			"콘서트 예매 서비스 회원가입이 완료되었습니다.\n\n" +
			"이제 로그인하시어 다양한 공연을 예매하실 수 있습니다.\n" +
			"가입 축하 포인트가 지급되었습니다. 마이페이지에서 확인해 보세요.\n\n" +
			"감사합니다.\n" +
			"콘서트 예매 팀",
			username
		);
	}
}
