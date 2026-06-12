package com.angus.timesurvey.controller;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.model.SurveyResponse;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 全站使用統計：供獨立統計頁（/stats）查詢所有調查的開啟次數、使用人數與回覆狀況 */
@RestController
public class StatsApiController {

    private final SurveyRepository surveyRepo;
    private final SurveyResponseRepository responseRepo;
    private final SurveyVisitRepository visitRepo;

    public StatsApiController(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo,
                              SurveyVisitRepository visitRepo) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
        this.visitRepo = visitRepo;
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        List<Survey> all = surveyRepo.findAll();
        all.sort(Comparator.comparing(Survey::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalVisits = 0;
        long totalResponded = 0;
        for (Survey s : all) {
            long visits = visitRepo.countBySurveyId(s.getId());
            long responded = responseRepo.findBySurveyId(s.getId()).stream()
                    .map(SurveyResponse::getParticipantName)
                    .filter(s.getParticipants()::contains)
                    .distinct().count();
            Map<String, Object> row = new HashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("startDate", s.getStartDate());
            row.put("endDate", s.getEndDate());
            row.put("closed", s.getClosedAt() != null);
            row.put("createdAt", s.getCreatedAt());
            row.put("visits", visits);
            row.put("uniqueIps", visitRepo.countDistinctIpBySurveyId(s.getId()));
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
}
