package com.inyoung.ticketing.common.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * [목적]
 * - 모든 API 성공 응답을 ApiResponse.success(...)로 감싸서 응답 포맷을 통일한다.
 *
 * [주의/예외]
 * - 파일/바이너리(Resource) 응답은 메시지 컨버터가 별도로 처리하므로 래핑하면 깨질 수 있어 제외한다.
 * - Actuator(/actuator/**)는 Prometheus/Health 등 "표준 포맷"을 그대로 내려야 하므로 래핑에서 제외한다.
 *   (예: /actuator/prometheus 는 text/plain 기반 포맷인데, 래핑하면 byte[] 캐스팅 오류(ClassCastException)가 날 수 있음)
 *
 * [특이 케이스]
 * - 컨트롤러가 String을 반환하면 StringHttpMessageConverter 경로로 타며,
 *   객체를 그대로 반환하면 JSON 변환이 아닌 "문자열 그대로" 응답될 수 있다.
 *   그래서 String 반환은 ApiResponse를 JSON 문자열로 직렬화해서 내려준다.
 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {
	private final ObjectMapper objectMapper;

	public ApiResponseAdvice(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		// 리소스(파일/바이너리) 응답을 래핑하지 않음
		if (org.springframework.http.converter.ResourceHttpMessageConverter.class.isAssignableFrom(converterType)) {
			return false;
		}
		return true;
	}

	@Override
	public Object beforeBodyWrite(
		Object body,
		MethodParameter returnType,
		MediaType selectedContentType,
		Class<? extends HttpMessageConverter<?>> selectedConverterType,
		ServerHttpRequest request,
		ServerHttpResponse response
	) {
		// Actuator(health, prometheus 등)는 텍스트/바이너리 그대로 반환. 래핑 시 ClassCastException 발생.
		if (request.getURI().getPath().startsWith("/actuator")) {
			return body;
		}
		if (body instanceof ErrorResponse || body instanceof ApiResponse) {
			return body;
		}

		ApiResponse<Object> wrapped = ApiResponse.success(body);
		if (body instanceof String) {
			try {
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				return objectMapper.writeValueAsString(wrapped);
			} catch (JsonProcessingException e) {
				return body;
			}
		}
		return wrapped;
	}
}
