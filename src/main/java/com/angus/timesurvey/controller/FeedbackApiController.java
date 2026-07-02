package com.angus.timesurvey.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 意見回饋功能的設定 API：收件者與主旨由 {@code data/feedback.properties}
 * 經 {@link com.angus.timesurvey.config.FeedbackEnvironmentPostProcessor} 注入。
 * 未設定收件者時回傳 204，前端據此隱藏「意見回饋」按鈕。
 */
@RestController
@RequestMapping("/api/feedback-config")
public class FeedbackApiController {

    private final String recipient;
    private final String subject;

    public FeedbackApiController(@Value("${feedback.recipient:}") String recipient,
                                  @Value("${feedback.subject:}") String subject) {
        this.recipient = recipient;
        this.subject = subject;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> get() {
        if (recipient == null || recipient.isBlank()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("recipient", recipient, "subject", subject));
    }
}
