package com.inyoung.ticketing.config;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.hold.event.SeatHoldEvent;
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

// Kafka 프로듀서 설정
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
}
