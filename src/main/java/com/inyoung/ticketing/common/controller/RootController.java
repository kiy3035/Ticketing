package com.inyoung.ticketing.common.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 루트 경로("/") 요청 처리
 * 
 * 미인증 사용자가 localhost:8080에 접속하면 /login.html로 리다이렉트합니다.
 */
@Controller
@RequestMapping("/")
public class RootController {
	
	/**
	 * 루트 경로 요청을 로그인 페이지로 리다이렉트
	 * 
	 * @return 로그인 페이지로의 리다이렉트
	 */
	@GetMapping
	public String redirectToLogin() {
		return "redirect:/login.html";
	}
}
