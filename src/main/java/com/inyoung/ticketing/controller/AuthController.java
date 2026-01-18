package com.inyoung.ticketing.controller;

import java.security.Principal;
import com.inyoung.ticketing.dto.SignupRequest;
import com.inyoung.ticketing.service.UserAccountService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

// 로그인/회원가입 화면 컨트롤러
@Controller
public class AuthController {
	private final UserAccountService userAccountService;

	// 서비스 주입
	public AuthController(UserAccountService userAccountService) {
		this.userAccountService = userAccountService;
	}

	// 로그인 화면
	@GetMapping("/login")
	public String loginPage() {
		return "login";
	}

	// 회원가입 화면
	@GetMapping("/signup")
	public String signupPage(Model model, Principal principal) {
		if (principal != null) {
			return "redirect:/app";
		}
		model.addAttribute("signupRequest", new SignupRequest());
		return "signup";
	}

	// 회원가입 처리
	@PostMapping("/signup")
	public String signup(@Valid SignupRequest signupRequest, BindingResult bindingResult) {
		if (bindingResult.hasErrors()) {
			return "signup";
		}
		userAccountService.signup(signupRequest);
		return "redirect:/login?signup";
	}
}
