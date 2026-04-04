package com.inyoung.ticketing.config;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.hold.event.SeatHoldEvent;
import com.inyoung.ticketing.payment.event.PaymentCompleteEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka Producer/Consumer/DLQ 설정.
 *
 * <p>에러 처리 전략:
 * <ul>
 *   <li>최대 3회 재시도 (1초 간격)</li>
 *   <li>재시도 실패 시 Dead Letter Topic({@code *.DLT})으로 전송</li>
 *   <li>DLT에서 수동 모니터링/재처리 가능</li>
 * </ul>
 */
@Configuration
@EnableKafka
public class KafkaConfig {
	private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

	// ── SeatHold Producer (JSON 직렬화) ──

	@Bean
	public ProducerFactory<String, SeatHoldEvent> seatHoldProducerFactory(
		KafkaProperties kafkaProperties, ObjectMapper objectMapper
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildProducerProperties());
		configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(configs, new StringSerializer(), new JsonSerializer<>(objectMapper));
	}

	@Bean
	public KafkaTemplate<String, SeatHoldEvent> seatHoldKafkaTemplate(
		ProducerFactory<String, SeatHoldEvent> seatHoldProducerFactory
	) {
		return new KafkaTemplate<>(seatHoldProducerFactory);
	}

	// ── SeatHold Consumer (JSON 역직렬화 — 기존 String 수동 파싱에서 통일) ──

	@Bean
	public ConsumerFactory<String, SeatHoldEvent> seatHoldConsumerFactory(
		KafkaProperties kafkaProperties, ObjectMapper objectMapper
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildConsumerProperties());
		configs.put("key.deserializer", StringDeserializer.class);
		JsonDeserializer<SeatHoldEvent> deserializer = new JsonDeserializer<>(SeatHoldEvent.class, objectMapper);
		deserializer.setRemoveTypeHeaders(true);
		deserializer.addTrustedPackages("com.inyoung.ticketing.*");
		deserializer.setUseTypeMapperForKey(false);
		return new DefaultKafkaConsumerFactory<>(configs, new StringDeserializer(), deserializer);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, SeatHoldEvent> seatHoldKafkaListenerFactory(
		ConsumerFactory<String, SeatHoldEvent> seatHoldConsumerFactory,
		KafkaTemplate<String, Object> dltKafkaTemplate
	) {
		ConcurrentKafkaListenerContainerFactory<String, SeatHoldEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(seatHoldConsumerFactory);
		factory.setCommonErrorHandler(createErrorHandler(dltKafkaTemplate));
		factory.getContainerProperties().setListenerTaskExecutor(virtualThreadExecutor("kafka-seat-hold-"));
		return factory;
	}

	// ── PaymentComplete Producer (JSON 직렬화) ──

	@Bean
	public ProducerFactory<String, PaymentCompleteEvent> paymentCompleteProducerFactory(
		KafkaProperties kafkaProperties, ObjectMapper objectMapper
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildProducerProperties());
		configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(configs, new StringSerializer(), new JsonSerializer<>(objectMapper));
	}

	@Bean
	public KafkaTemplate<String, PaymentCompleteEvent> paymentCompleteKafkaTemplate(
		ProducerFactory<String, PaymentCompleteEvent> paymentCompleteProducerFactory
	) {
		return new KafkaTemplate<>(paymentCompleteProducerFactory);
	}

	// ── PaymentComplete Consumer (JSON 역직렬화) ──

	@Bean
	public ConsumerFactory<String, PaymentCompleteEvent> paymentCompleteConsumerFactory(
		KafkaProperties kafkaProperties, ObjectMapper objectMapper
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildConsumerProperties());
		configs.put("key.deserializer", StringDeserializer.class);
		JsonDeserializer<PaymentCompleteEvent> deserializer = new JsonDeserializer<>(PaymentCompleteEvent.class, objectMapper);
		deserializer.setRemoveTypeHeaders(true);
		deserializer.addTrustedPackages("com.inyoung.ticketing.*");
		deserializer.setUseTypeMapperForKey(false);
		return new DefaultKafkaConsumerFactory<>(configs, new StringDeserializer(), deserializer);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, PaymentCompleteEvent> paymentCompleteKafkaListenerFactory(
		ConsumerFactory<String, PaymentCompleteEvent> paymentCompleteConsumerFactory,
		KafkaTemplate<String, Object> dltKafkaTemplate
	) {
		ConcurrentKafkaListenerContainerFactory<String, PaymentCompleteEvent> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(paymentCompleteConsumerFactory);
		factory.setCommonErrorHandler(createErrorHandler(dltKafkaTemplate));
		factory.getContainerProperties().setListenerTaskExecutor(virtualThreadExecutor("kafka-payment-"));
		return factory;
	}

	// ── DLT(Dead Letter Topic) 공통 Producer ──

	@Bean
	public ProducerFactory<String, Object> dltProducerFactory(KafkaProperties kafkaProperties) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildProducerProperties());
		configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(configs);
	}

	@Bean
	public KafkaTemplate<String, Object> dltKafkaTemplate(ProducerFactory<String, Object> dltProducerFactory) {
		return new KafkaTemplate<>(dltProducerFactory);
	}

	// Kafka 리스너 스레드를 Virtual Thread로 전환.
	// 이벤트 처리 시 DB 조회·이메일 발송 등 I/O 대기가 발생하므로 Platform Thread를 점유하지 않도록 한다.
	private SimpleAsyncTaskExecutor virtualThreadExecutor(String prefix) {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(prefix);
		executor.setVirtualThreads(true);
		return executor;
	}

	/**
	 * 공통 에러 핸들러: 3회 재시도(1초 간격) 실패 시 DLT로 전송.
	 * DLT 토픽 이름은 원래 토픽 + ".DLT" (예: ticketing.seat-hold-events.DLT)
	 */
	private CommonErrorHandler createErrorHandler(KafkaTemplate<String, Object> dltKafkaTemplate) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate);
		DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
		errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
		return errorHandler;
	}
}
