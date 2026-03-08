package com.inyoung.ticketing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.EnableScheduling;

// 애플리케이션 진입점: 컴포넌트 스캔과 캐시/스케줄링을 활성화한다.
@SpringBootApplication
@EnableCaching // Redis 캐시 활성화
@EnableScheduling // 홀드 정리 스케줄러 실행
public class TicketingApplication {

	public static void main(String[] args) {
		// #region agent log
		writeDebugLog("TicketingApplication.main", "main entered", null, "H-main");
		// #endregion
		SpringApplication app = new SpringApplication(TicketingApplication.class);
		app.addListeners((ApplicationListener<ApplicationFailedEvent>) event -> {
			// #region agent log
			Throwable t = event.getException();
			String msg = t != null ? t.getMessage() : "null";
			String cause = t != null && t.getCause() != null ? t.getCause().getClass().getName() + ": " + t.getCause().getMessage() : "";
			writeDebugLog("TicketingApplication.ApplicationFailedEvent", "startup failed",
				"exception=" + (t != null ? t.getClass().getName() : "null") + ", message=" + msg + ", cause=" + cause, "H-fail");
			// #endregion
		});
		app.run(args);
	}

	// #region agent log
	private static void writeDebugLog(String location, String message, String data, String hypothesisId) {
		try {
			String dataJson = data != null ? ",\"data\":\"" + data.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"" : "";
			String line = "{\"sessionId\":\"891a0e\",\"timestamp\":" + Instant.now().toEpochMilli()
				+ ",\"location\":\"" + location + "\",\"message\":\"" + message.replace("\"", "\\\"") + "\""
				+ dataJson + ",\"hypothesisId\":\"" + hypothesisId + "\"}\n";
			Files.write(Paths.get("debug-891a0e.log"), line.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Throwable ignored) { }
	}
	// #endregion
}
