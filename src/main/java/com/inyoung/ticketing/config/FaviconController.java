package com.inyoung.ticketing.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// favicon.ico 및 Chrome DevTools 자동 요청 처리 (404 에러 방지)
@RestController
public class FaviconController {

	@GetMapping("/favicon.ico")
	public ResponseEntity<Resource> favicon() {
		Resource favicon = new ClassPathResource("static/favicon.png");
		if (!favicon.exists()) {
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		}
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_PNG)
			.body(favicon);
	}

	@GetMapping("/.well-known/**")
	public ResponseEntity<Void> wellKnown() {
        // Chrome DevTools 등 브라우저 자동 요청 처리
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}
}
