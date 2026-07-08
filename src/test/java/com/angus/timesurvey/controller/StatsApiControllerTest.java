package com.angus.timesurvey.controller;

import com.angus.timesurvey.model.UserActivity;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import com.angus.timesurvey.repo.UserActivityRepository;
import com.angus.timesurvey.service.EntraGraphService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 驗證使用量統計 API：未啟用 Entra ID 時回 enabled=false；
 * 啟用時依區間彙總各登入者的開啟次數、補零的每日趨勢，並支援個人篩選。
 */
class StatsApiControllerTest {

    private final UserActivityRepository activityRepo = mock(UserActivityRepository.class);
    private final EntraGraphService graph = mock(EntraGraphService.class);
    private final StatsApiController controller = new StatsApiController(
            mock(SurveyRepository.class), mock(SurveyResponseRepository.class),
            mock(SurveyVisitRepository.class), activityRepo, graph);

    private static UserActivity act(String userId, String name, String email, LocalDateTime at) {
        return act(userId, name, email, "/", at);
    }

    private static UserActivity act(String userId, String name, String email, String page, LocalDateTime at) {
        UserActivity a = new UserActivity();
        a.setUserId(userId);
        a.setUserName(name);
        a.setUserEmail(email);
        a.setPage(page);
        a.setOccurredAt(at);
        return a;
    }

    @Test
    void 未啟用Entra時回enabled為false() {
        when(graph.enabled()).thenReturn(false);
        assertEquals(Map.of("enabled", false), controller.usage("2w", null));
        verify(activityRepo, never()).findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(any());
    }

    @Test
    void 整體統計彙總各登入者次數與補零趨勢() {
        when(graph.enabled()).thenReturn(true);
        LocalDateTime now = LocalDateTime.now();
        when(activityRepo.findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(any())).thenReturn(List.of(
                act("u1", "王小明", "ming@example.com", now.minusDays(3)),
                act("u1", "王小明", "ming@example.com", now.minusDays(1)),
                act("u2", "陳大文", "tai@example.com", now.minusDays(1))));

        Map<String, Object> d = controller.usage("2w", null);
        assertEquals(true, d.get("enabled"));
        assertEquals(3L, d.get("totalOpens"));
        assertEquals(2L, d.get("totalUsers"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) d.get("users");
        assertEquals(2, users.size());
        assertEquals("王小明", users.get(0).get("name"));   // 次數多者排前
        assertEquals(2L, users.get(0).get("opens"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) d.get("trend");
        assertEquals(15, trend.size());   // 近兩週：起日到今日共 15 天，無紀錄的天數補零
        long sum = trend.stream().mapToLong(t -> (Long) t.get("count")).sum();
        assertEquals(3, sum);
    }

    @Test
    void 指定email時趨勢與次數僅計該員但明細表仍列全員() {
        when(graph.enabled()).thenReturn(true);
        LocalDateTime now = LocalDateTime.now();
        when(activityRepo.findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(any())).thenReturn(List.of(
                act("u1", "王小明", "ming@example.com", now.minusDays(2)),
                act("u2", "陳大文", "tai@example.com", now.minusDays(1))));

        Map<String, Object> d = controller.usage("2w", "MING@example.com");
        assertEquals(1L, d.get("totalOpens"));
        assertEquals(1L, d.get("totalUsers"));
        assertEquals(2, ((List<?>) d.get("users")).size());
    }

    @Test
    void 發出調查與回覆時間分開統計次數與人數() {
        when(graph.enabled()).thenReturn(true);
        LocalDateTime now = LocalDateTime.now();
        when(activityRepo.findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(any())).thenReturn(List.of(
                act("u1", "王小明", "ming@example.com", "/", now.minusDays(3)),
                act("u1", "王小明", "ming@example.com", "/s/abc123", now.minusDays(2)),
                act("u2", "陳大文", "tai@example.com", "/s/abc123", now.minusDays(1)),
                act("u2", "陳大文", "tai@example.com", "/stats", now.minusDays(1))));

        Map<String, Object> d = controller.usage("2w", null);
        assertEquals(1L, d.get("createOpens"));   // 首頁開啟：僅 u1 一次
        assertEquals(1L, d.get("createUsers"));
        assertEquals(2L, d.get("replyOpens"));    // /s/** 開啟：u1、u2 各一次
        assertEquals(2L, d.get("replyUsers"));
        assertEquals(4L, d.get("totalOpens"));    // /stats 不屬兩類但仍計入總開啟

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) d.get("users");
        Map<String, Object> u1 = users.stream().filter(u -> "王小明".equals(u.get("name"))).findFirst().orElseThrow();
        assertEquals(1L, u1.get("createOpens"));
        assertEquals(1L, u1.get("replyOpens"));
        Map<String, Object> u2 = users.stream().filter(u -> "陳大文".equals(u.get("name"))).findFirst().orElseThrow();
        assertEquals(0L, u2.get("createOpens"));
        assertEquals(1L, u2.get("replyOpens"));

        // 指定個人時，兩類統計同樣僅計該員
        Map<String, Object> personal = controller.usage("2w", "tai@example.com");
        assertEquals(0L, personal.get("createOpens"));
        assertEquals(1L, personal.get("replyOpens"));
    }

    @Test
    void 統計區間起日對應各期間() {
        LocalDate today = LocalDate.of(2026, 7, 7);
        assertEquals(today.minusWeeks(2), StatsApiController.periodStart("2w", today));
        assertEquals(today.minusMonths(1), StatsApiController.periodStart("1m", today));
        assertEquals(today.minusMonths(3), StatsApiController.periodStart("3m", today));
        assertEquals(today.minusMonths(6), StatsApiController.periodStart("6m", today));
        assertEquals(today.minusWeeks(2), StatsApiController.periodStart("bogus", today));
        assertEquals(today.minusWeeks(2), StatsApiController.periodStart(null, today));
    }
}
