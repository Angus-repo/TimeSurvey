package com.angus.timesurvey.controller;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.model.SurveyResponse;
import com.angus.timesurvey.model.UserActivity;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import com.angus.timesurvey.repo.UserActivityRepository;
import com.angus.timesurvey.service.EntraGraphService;
import com.angus.timesurvey.service.SurveyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 全站使用統計：供獨立統計頁（/stats）查詢所有調查的開啟次數、使用人數與回覆狀況 */
@RestController
public class StatsApiController {

    private final SurveyRepository surveyRepo;
    private final SurveyResponseRepository responseRepo;
    private final SurveyVisitRepository visitRepo;
    private final UserActivityRepository activityRepo;
    private final EntraGraphService graph;

    public StatsApiController(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo,
                              SurveyVisitRepository visitRepo, UserActivityRepository activityRepo,
                              EntraGraphService graph) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
        this.visitRepo = visitRepo;
        this.activityRepo = activityRepo;
        this.graph = graph;
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        List<Survey> all = surveyRepo.findAll();
        all.sort(Comparator.comparing(Survey::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        // 各調查的造訪數、IP 數、回覆各以一次查詢撈齊，避免逐筆調查各查三次
        Map<String, Long> visitCounts = countMap(visitRepo.countGroupBySurveyId());
        Map<String, Long> ipCounts = countMap(visitRepo.countDistinctIpGroupBySurveyId());
        Map<String, List<SurveyResponse>> responsesBySurvey = responseRepo.findAll().stream()
                .collect(Collectors.groupingBy(SurveyResponse::getSurveyId));

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalVisits = 0;
        long totalResponded = 0;
        for (Survey s : all) {
            long visits = visitCounts.getOrDefault(s.getId(), 0L);
            long responded = SurveyService.respondedCount(s,
                    responsesBySurvey.getOrDefault(s.getId(), List.of()));
            Map<String, Object> row = new HashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("startDate", s.getStartDate());
            row.put("endDate", s.getEndDate());
            row.put("closed", s.getClosedAt() != null);
            row.put("createdAt", s.getCreatedAt());
            row.put("visits", visits);
            row.put("uniqueIps", ipCounts.getOrDefault(s.getId(), 0L));
            row.put("responded", responded);
            row.put("total", s.getParticipants().size());
            rows.add(row);
            totalVisits += visits;
            totalResponded += responded;
        }
        return Map.of(
                "totalSurveys", all.size(),
                "totalVisits", totalVisits,
                "totalUniqueIps", visitRepo.countDistinctIpAll(),
                "totalResponded", totalResponded,
                "generatedAt", LocalDateTime.now(),
                "surveys", rows);
    }

    /**
     * Entra ID 登入者的使用量統計（未啟用 Entra ID 時回 enabled=false，前端不顯示此區塊）。
     *
     * @param period 統計區間：2w=近兩週、1m=近一個月、3m=近三個月、6m=近半年
     * @param user   指定登入者 email 看個人統計；未指定為整體統計
     */
    @GetMapping("/api/stats/usage")
    public Map<String, Object> usage(@RequestParam(defaultValue = "2w") String period,
                                     @RequestParam(required = false) String user) {
        if (!graph.enabled()) {
            return Map.of("enabled", false);
        }
        LocalDate from = periodStart(period, LocalDate.now());
        List<UserActivity> acts = activityRepo
                .findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(from.atStartOfDay());

        // 各登入者統計（依整段區間計，供前端做人員下拉選單與明細表）
        // 依開啟頁面分「發出調查」（首頁）與「回覆時間」（/s/** 調查頁）兩類分開計數
        Map<String, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (UserActivity a : acts) {
            Map<String, Object> u = byUser.computeIfAbsent(a.getUserId(), k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("opens", 0L);
                m.put("createOpens", 0L);
                m.put("replyOpens", 0L);
                return m;
            });
            // 姓名、email 以最新一筆為準（改名、換 UPN 時顯示新值）
            u.put("name", a.getUserName() == null ? "" : a.getUserName());
            u.put("email", a.getUserEmail() == null ? "" : a.getUserEmail());
            u.put("opens", (Long) u.get("opens") + 1);
            if (isCreatePage(a.getPage())) {
                u.put("createOpens", (Long) u.get("createOpens") + 1);
            } else if (isReplyPage(a.getPage())) {
                u.put("replyOpens", (Long) u.get("replyOpens") + 1);
            }
            u.put("lastAt", a.getOccurredAt());
        }
        List<Map<String, Object>> users = new ArrayList<>(byUser.values());
        users.sort(Comparator.comparingLong(m -> -((Long) m.get("opens"))));

        // 每日趨勢（從區間起日補零到今日）；指定 user 時僅計該員。
        // 每天記三個數：opens=頁面開啟次數、creates=發起調查次數（首頁）、replies=參與調查人次（/s/** 調查頁）
        List<UserActivity> scoped = user == null || user.isBlank() ? acts
                : acts.stream().filter(a -> user.equalsIgnoreCase(a.getUserEmail())).toList();
        Map<LocalDate, long[]> daily = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            daily.put(d, new long[3]);
        }
        for (UserActivity a : scoped) {
            long[] c = daily.computeIfAbsent(a.getOccurredAt().toLocalDate(), k -> new long[3]);
            c[0]++;
            if (isCreatePage(a.getPage())) {
                c[1]++;
            } else if (isReplyPage(a.getPage())) {
                c[2]++;
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        daily.forEach((d, c) -> trend.add(Map.of(
                "date", d.toString(), "count", c[0], "creates", c[1], "replies", c[2])));

        long scopedUsers = scoped.stream().map(UserActivity::getUserId).distinct().count();
        // 發出調查／回覆時間的次數與人數（指定 user 時同樣僅計該員）
        List<UserActivity> creates = scoped.stream().filter(a -> isCreatePage(a.getPage())).toList();
        List<UserActivity> replies = scoped.stream().filter(a -> isReplyPage(a.getPage())).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("period", period);
        result.put("from", from.toString());
        result.put("totalOpens", (long) scoped.size());
        result.put("totalUsers", scopedUsers);
        result.put("createOpens", (long) creates.size());
        result.put("createUsers", creates.stream().map(UserActivity::getUserId).distinct().count());
        result.put("replyOpens", (long) replies.size());
        result.put("replyUsers", replies.stream().map(UserActivity::getUserId).distinct().count());
        result.put("users", users);
        result.put("trend", trend);
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    /** GROUP BY 查詢結果（[surveyId, count] 列）轉成 Map */
    private static Map<String, Long> countMap(List<Object[]> rows) {
        Map<String, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((String) r[0], (Long) r[1]);
        }
        return m;
    }

    /** 發出調查的頁面：首頁（建立與管理調查） */
    static boolean isCreatePage(String page) {
        return "/".equals(page) || "/index.html".equals(page);
    }

    /** 回覆會議時間的頁面：/s/{id} 調查填答頁 */
    static boolean isReplyPage(String page) {
        return page != null && page.startsWith("/s/");
    }

    /** 統計區間起日：2w=近兩週、1m=近一個月、3m=近三個月、6m=近半年（其餘視為 2w） */
    static LocalDate periodStart(String period, LocalDate today) {
        return switch (period == null ? "" : period) {
            case "1m" -> today.minusMonths(1);
            case "3m" -> today.minusMonths(3);
            case "6m" -> today.minusMonths(6);
            default -> today.minusWeeks(2);
        };
    }
}
