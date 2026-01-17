package com.inyoung.ticketing.controller;

import com.inyoung.ticketing.dto.HoldCreateRequest;
import com.inyoung.ticketing.dto.HoldResponse;
import com.inyoung.ticketing.service.HoldService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holds")
public class HoldController {
	private final HoldService holdService;

	public HoldController(HoldService holdService) {
		this.holdService = holdService;
	}

	@PostMapping
	public HoldResponse createHold(@Valid @RequestBody HoldCreateRequest request) {
		return holdService.createHold(request);
	}

	@DeleteMapping("/{holdId}")
	public ResponseEntity<Void> cancelHold(@PathVariable Long holdId) {
		holdService.cancelHold(holdId);
		return ResponseEntity.noContent().build();
	}
}
