package com.inyoung.ticketing.controller;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// 로그인 이후에 보이는 화면 컨트롤러
@Controller
public class PageController {
	// 루트 진입 시 앱 메인으로 이동
	@GetMapping("/")
	public String root() {
		return "redirect:/app";
	}

	// 콘서트 목록 메인 화면
	@GetMapping("/app")
	public String app(Model model, Principal principal) {
		model.addAttribute("username", principal.getName());
		return "app";
	}

	// 콘서트 상세(좌석) 화면
	@GetMapping("/app/concert/{concertId}")
	public String concertDetail(@PathVariable Long concertId, Model model, Principal principal) {
		model.addAttribute("concertId", concertId);
		model.addAttribute("username", principal.getName());
		return "concert-detail";
	}
}
