package com.inyoung.ticketing.config;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.hold.event.SeatHoldEvent;
import com.inyoung.ticketing.payment.event.PaymentCompleteEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;

// Kafka Producer/Consumer Factory 및 ListenerContainerFactory 설정.
// 홀드 이벤트(SeatHoldEvent)와 결제 완료 이벤트(PaymentCompleteEvent) 두 종류를
// 별도의 직렬화/역직렬화 체인으로 분리 구성한다.
@Configuration
@EnableKafka
public class KafkaConfig {
	@Bean
	public ProducerFactory<String, SeatHoldEvent> seatHoldProducerFactory(
		KafkaProperties kafkaProperties,
		ObjectMapper objectMapper
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildProducerProperties());
		configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(
			configs,
			new StringSerializer(),
			new JsonSerializer<>(objectMapper)
		);
	}

	@Bean
	public KafkaTemplate<String, SeatHoldEvent> seatHoldKafkaTemplate(
		ProducerFactory<String, SeatHoldEvent> seatHoldProducerFactory
	) {
		return new KafkaTemplate<>(seatHoldProducerFactory);
	}

	@Bean
	public ConsumerFactory<String, String> seatHoldConsumerFactory(
		KafkaProperties kafkaProperties
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildConsumerProperties());
		configs.put("key.deserializer", StringDeserializer.class);
		configs.put("value.deserializer", StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(
			configs,
			new StringDeserializer(),
			new StringDeserializer()
		);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> seatHoldKafkaListenerFactory(
		ConsumerFactory<String, String> seatHoldConsumerFactory
	) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(seatHoldConsumerFactory);
		return factory;
	}

	@Bean
	public ProducerFactory<String, PaymentCompleteEvent> paymentCompleteProducerFactory(
		KafkaProperties kafkaProperties,
		ObjectMapper objectMapper
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildProducerProperties());
		configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
		return new DefaultKafkaProducerFactory<>(
			configs,
			new StringSerializer(),
			new JsonSerializer<>(objectMapper)
		);
	}

	@Bean
	public KafkaTemplate<String, PaymentCompleteEvent> paymentCompleteKafkaTemplate(
		ProducerFactory<String, PaymentCompleteEvent> paymentCompleteProducerFactory
	) {
		return new KafkaTemplate<>(paymentCompleteProducerFactory);
	}

	@Bean
	public ConsumerFactory<String, PaymentCompleteEvent> paymentCompleteConsumerFactory(
		KafkaProperties kafkaProperties,
		ObjectMapper objectMapper
	) {
		Map<String, Object> configs = new HashMap<>(kafkaProperties.buildConsumerProperties());
		configs.put("key.deserializer", StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(
			configs,
			new StringDeserializer(),
			new org.springframework.kafka.support.serializer.JsonDeserializer<>(PaymentCompleteEvent.class, false)
		);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, PaymentCompleteEvent> paymentCompleteKafkaListenerFactory(
		ConsumerFactory<String, PaymentCompleteEvent> paymentCompleteConsumerFactory
	) {
		ConcurrentKafkaListenerContainerFactory<String, PaymentCompleteEvent> factory =
			new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(paymentCompleteConsumerFactory);
		return factory;
	}
}
