package com.inyoung.ticketing.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// favicon.ico 및 Chrome DevTools 자동 요청 처리 (404 에러 방지)
@RestController
public class FaviconController {
    
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        // favicon 파일이 없으므로 204 No Content 반환 (에러 로그 없음)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
    
    @GetMapping("/.well-known/**")
    public ResponseEntity<Void> wellKnown() {
        // Chrome DevTools 등 브라우저 자동 요청 처리
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
