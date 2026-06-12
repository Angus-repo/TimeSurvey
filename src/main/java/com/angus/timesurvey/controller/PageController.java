package com.angus.timesurvey.controller;

import com.angus.timesurvey.model.SurveyVisit;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDateTime;

@Controller
public class PageController {

    private final SurveyRepository surveyRepo;
    private final SurveyVisitRepository visitRepo;

    public PageController(SurveyRepository surveyRepo, SurveyVisitRepository visitRepo) {
        this.surveyRepo = surveyRepo;
        this.visitRepo = visitRepo;
    }

    /** 調查連結：/s/{調查ID} → 調查填寫頁；同時記錄一次開啟（含來源 IP）供統計 */
    @GetMapping("/s/{id}")
    public String surveyPage(@PathVariable String id, HttpServletRequest request) {
        if (surveyRepo.existsById(id)) {
            SurveyVisit v = new SurveyVisit();
            v.setSurveyId(id);
            v.setIp(clientIp(request));
            v.setVisitedAt(LocalDateTime.now());
            visitRepo.save(v);
        }
        return "forward:/survey.html";
    }

    /** 全站使用統計頁（管理者自行進入查看） */
    @GetMapping("/stats")
    public String statsPage() {
        return "forward:/stats.html";
    }

    /** 來源 IP：經過反向代理時取 X-Forwarded-For 的第一段，否則取連線位址 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
