package com.example.timesurvey.controller;

import com.example.timesurvey.model.Survey;
import com.example.timesurvey.model.SurveyResponse;
import com.example.timesurvey.repo.SurveyRepository;
import com.example.timesurvey.repo.SurveyResponseRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/surveys")
public class SurveyApiController {

    private final SurveyRepository surveyRepo;
    private final SurveyResponseRepository responseRepo;

    public SurveyApiController(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
    }

    @GetMapping
    public List<Survey> list() {
        return surveyRepo.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    public Survey get(@PathVariable String id) {
        return surveyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "調查不存在"));
    }

    @PostMapping
    public Survey create(@RequestBody Survey survey) {
        validate(survey);
        survey.setId(UUID.randomUUID().toString());
        survey.setCreatedAt(LocalDateTime.now());
        return surveyRepo.save(survey);
    }

    @PutMapping("/{id}")
    public Survey update(@PathVariable String id, @RequestBody Survey survey) {
        Survey existing = get(id);
        validate(survey);
        existing.setName(survey.getName());
        existing.setStartDate(survey.getStartDate());
        existing.setEndDate(survey.getEndDate());
        existing.setStartTime(survey.getStartTime());
        existing.setEndTime(survey.getEndTime());
        existing.setParticipants(survey.getParticipants());
        return surveyRepo.save(existing);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable String id) {
        responseRepo.deleteBySurveyId(id);
        surveyRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/responses")
    public List<SurveyResponse> responses(@PathVariable String id) {
        get(id);
        return responseRepo.findBySurveyId(id);
    }

    /** 儲存（或覆寫）某參與者的勾選結果 */
    @PostMapping("/{id}/responses")
    public SurveyResponse saveResponse(@PathVariable String id, @RequestBody Map<String, String> body) {
        Survey survey = get(id);
        String name = body.getOrDefault("participantName", "").trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請選擇參與者姓名");
        }
        if (!survey.getParticipants().contains(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此姓名不在受調查人員名單中");
        }
        SurveyResponse resp = responseRepo.findBySurveyIdAndParticipantName(id, name)
                .orElseGet(() -> {
                    SurveyResponse r = new SurveyResponse();
                    r.setSurveyId(id);
                    r.setParticipantName(name);
                    return r;
                });
        resp.setSlots(body.getOrDefault("slots", ""));
        resp.setUpdatedAt(LocalDateTime.now());
        return responseRepo.save(resp);
    }

    private void validate(Survey s) {
        if (s.getName() == null || s.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請輸入調查名稱");
        }
        if (s.getStartDate() == null || s.getEndDate() == null || s.getEndDate().isBefore(s.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期範圍不正確");
        }
        if (s.getStartTime() == null || s.getEndTime() == null || !s.getEndTime().isAfter(s.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "時間範圍不正確");
        }
        if (s.getParticipants() == null || s.getParticipants().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請至少輸入一位受調查人員");
        }
    }
}
