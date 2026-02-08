package com.inyoung.ticketing.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.nurigo.sdk.NurigoApp;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.service.DefaultMessageService;

/**
 * Solapi를 이용한 SMS 전송 서비스
 * 
 * 결제 완료, 예약 확정 등의 알림을 SMS로 전송합니다.
 * Solapi: https://solapi.com/ (한국 최대 문자 서비스 플랫폼)
 * 
 * API: RestAPI 기반 메시지 발송
 * 인증: API Key + API Secret 기반 인증
 */
@Service
public class SmsService {
	private static final Logger logger = LoggerFactory.getLogger(SmsService.class);

	@Value("${solapi.api-key}")
	private String apiKey;

	@Value("${solapi.api-secret}")
	private String apiSecret;

	@Value("${solapi.from-number}")
	private String fromNumber;

	@Value("${solapi.api-url}")
	private String apiUrl;

	private DefaultMessageService messageService;

	/**
	 * Solapi 메시지 서비스 초기화 (Lazy initialization)
	 * 첫 SMS 전송 시 한 번만 초기화되어 리소스 절약
	 */
	private void initializeMessageService() {
		if (messageService == null) {
			messageService = NurigoApp.INSTANCE.initialize(apiKey, apiSecret, apiUrl);
		}
	}

	/**
	 * 결제 완료 SMS 전송
	 * 
	 * @param phone 수신자 휴대폰 번호 (010-0000-0000 또는 01000000000 형식)
	 * @param username 사용자명
	 * @param concertName 공연명
	 * @param amount 결제 금액
	 * 
	 * Exception 발생 시:
	 * - Solapi API 오류 → 로그 기록, 런타임 예외 발생
	 * - 네트워크 오류 → 로그 기록, 런타임 예외 발생
	 * - 알림 실패가 결제 프로세스를 방해하지 않음 (비동기 처리)
	 */
	public void sendPaymentCompleteSms(String phone, String username, String concertName, long amount) {
		try {
			initializeMessageService();

			// 휴대폰 번호 포맷팅 (하이픈 제거: 010-1234-5678 → 01012345678)
			String formattedPhone = phone.replaceAll("-", "");

			// Solapi Message 객체 생성
			Message message = new Message();
			message.setFrom(fromNumber);           // 발신 번호 (Solapi에 등록한 번호)
			message.setTo(formattedPhone);         // 수신 번호
			message.setText(buildPaymentCompleteSmsBody(username, concertName, amount));

			// Solapi를 통해 SMS 발송
			messageService.send(message);

			logger.info("SMS sent successfully to: {} (concert: {}, amount: {})", formattedPhone, concertName, amount);
		} catch (Exception e) {
			// 예외 처리 (API 오류, 네트워크 오류 등)
			logger.error("Failed to send SMS to {}: {}", phone, e.getMessage(), e);
			throw new RuntimeException("Failed to send SMS via Solapi", e);
		}
	}

	/**
	 * 결제 완료 SMS 본문 작성
	 * 
	 * @param username 사용자명
	 * @param concertName 공연명
	 * @param amount 결제 금액
	 * @return SMS 본문 텍스트 (한글 45자 이내)
	 */
	private String buildPaymentCompleteSmsBody(String username, String concertName, long amount) {
		return String.format(
			"[예매완료] %s님 %,d원",
			username,
			amount
		);
	}
}
