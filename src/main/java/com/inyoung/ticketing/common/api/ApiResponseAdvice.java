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

// 공통 성공 응답 래퍼 적용
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
