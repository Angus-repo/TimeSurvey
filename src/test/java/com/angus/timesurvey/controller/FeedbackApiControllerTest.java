package com.angus.timesurvey.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 驗證意見回饋設定 API：未設定收件者回傳 204，已設定則回傳收件者與主旨。 */
class FeedbackApiControllerTest {

    @Test
    void 未設定收件者時回傳204() {
        FeedbackApiController controller = new FeedbackApiController("", "");
        ResponseEntity<Map<String, String>> res = controller.get();
        assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
    }

    @Test
    void 收件者為空白字元時仍視為未設定() {
        FeedbackApiController controller = new FeedbackApiController("   ", "主旨");
        assertEquals(HttpStatus.NO_CONTENT, controller.get().getStatusCode());
    }

    @Test
    void 已設定收件者時回傳收件者與主旨() {
        FeedbackApiController controller = new FeedbackApiController("a@b.com", "[TimeSurvey] 意見回饋");
        ResponseEntity<Map<String, String>> res = controller.get();
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("a@b.com", res.getBody().get("recipient"));
        assertEquals("[TimeSurvey] 意見回饋", res.getBody().get("subject"));
    }
}
