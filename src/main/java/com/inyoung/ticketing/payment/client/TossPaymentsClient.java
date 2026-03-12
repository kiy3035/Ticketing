package com.inyoung.ticketing.payment.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import com.inyoung.ticketing.config.TicketingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * 토스페이먼츠 REST API 호출 클라이언트.
 * <p>
 * 샌드박스(test_sk_...) 사용 시 모의결제만 수행되며 실제 카드 출금/입금은 발생하지 않는다.
 * 시크릿 키는 {@link TicketingProperties#getToss()} 에서 읽으며, .env 의 TOSS_SECRET_KEY 로 주입.
 */
@Component
public class TossPaymentsClient {
	private static final Logger log = LoggerFactory.getLogger(TossPaymentsClient.class);
	private static final String BASE_URL = "https://api.tosspayments.com";

	private final RestTemplate restTemplate;
	private final TicketingProperties properties;

	public TossPaymentsClient(RestTemplate restTemplate, TicketingProperties properties) {
		this.restTemplate = restTemplate;
		this.properties = properties;
	}

	/**
	 * 결제 승인 API 호출 (인증 완료 후 우리 서버에서 1회만 호출).
	 * <p>
	 * 토스 문서: POST https://api.tosspayments.com/v1/payments/confirm,
	 * Authorization: Basic base64(secretKey + ":"), Body: paymentKey, orderId, amount.
	 *
	 * @param paymentKey 토스 successUrl 리다이렉트로 전달된 paymentKey
	 * @param orderId    결제 요청 시 우리가 부여한 orderId (payment.orderId 와 일치)
	 * @param amount    결제 금액(원)
	 * @return 성공 시 토스 응답 맵, 실패 시 RestClientResponseException
	 */
	public Map<String, Object> confirmPayment(String paymentKey, String orderId, long amount) {
		String secretKey = properties.getToss().getSecretKey();
		if (secretKey == null || secretKey.isBlank()) {
			throw new IllegalStateException("Toss secret key is not configured");
		}
		// 토스 인증: Basic base64(secretKey + ":")
		String auth = "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Authorization", auth);

		Map<String, Object> body = Map.of(
			"paymentKey", paymentKey,
			"orderId", orderId,
			"amount", amount
		);
		HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
		try {
			ResponseEntity<Map> response = restTemplate.postForEntity(
				BASE_URL + "/v1/payments/confirm",
				entity,
				Map.class
			);
			if (response.getBody() != null) {
				log.debug("Toss confirm success orderId={}", orderId);
				return response.getBody();
			}
			throw new IllegalStateException("Toss confirm returned empty body");
		} catch (RestClientResponseException e) {
			log.warn("Toss confirm failed orderId={} status={} body={}", orderId, e.getStatusCode(), e.getResponseBodyAsString());
			throw e;
		}
	}
}
