package com.example.timesurvey.controller;

import com.example.timesurvey.model.Survey;
import com.example.timesurvey.model.SurveyResponse;
import com.example.timesurvey.repo.SurveyRepository;
import com.example.timesurvey.repo.SurveyResponseRepository;
import com.example.timesurvey.ws.NotifyWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final NotifyWebSocketHandler notifier;
    private final ObjectMapper objectMapper;

    public SurveyApiController(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo,
                               NotifyWebSocketHandler notifier, ObjectMapper objectMapper) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
    }

    /** 後台清單：只回傳該發起者（owner token）自己建立的調查 */
    @GetMapping
    public List<Survey> list(@RequestHeader(value = "X-Owner-Token", required = false) String owner) {
        if (owner == null || owner.isBlank()) {
            return List.of();
        }
        return surveyRepo.findByOwnerTokenOrderByCreatedAtDesc(owner);
    }

    @GetMapping("/{id}")
    public Survey get(@PathVariable String id) {
        return surveyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "調查不存在"));
    }

    @PostMapping
    public Survey create(@RequestBody Survey survey,
                         @RequestHeader(value = "X-Owner-Token", required = false) String owner) {
        if (owner == null || owner.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少發起者識別碼");
        }
        validate(survey);
        survey.setId(UUID.randomUUID().toString());
        survey.setOwnerToken(owner);
        survey.setCreatedAt(LocalDateTime.now());
        return surveyRepo.save(survey);
    }

    @PutMapping("/{id}")
    public Survey update(@PathVariable String id, @RequestBody Survey survey,
                         @RequestHeader(value = "X-Owner-Token", required = false) String owner) {
        Survey existing = getOwned(id, owner);
        validate(survey);
        existing.setName(survey.getName());
        existing.setStartDate(survey.getStartDate());
        existing.setEndDate(survey.getEndDate());
        existing.setStartTime(survey.getStartTime());
        existing.setEndTime(survey.getEndTime());
        existing.setParticipants(survey.getParticipants());
        return surveyRepo.save(existing);
    }

    /** 結束調查：之後參與者不能再填寫 */
    @PostMapping("/{id}/close")
    public Survey close(@PathVariable String id,
                        @RequestHeader(value = "X-Owner-Token", required = false) String owner) {
        Survey s = getOwned(id, owner);
        if (s.getClosedAt() == null) {
            s.setClosedAt(LocalDateTime.now());
            s = surveyRepo.save(s);
        }
        return s;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestHeader(value = "X-Owner-Token", required = false) String owner) {
        getOwned(id, owner);
        responseRepo.deleteBySurveyId(id);
        surveyRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** 取得調查並確認是本人發起，否則 403 */
    private Survey getOwned(String id, String owner) {
        Survey s = get(id);
        if (owner == null || !owner.equals(s.getOwnerToken())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能操作自己發起的調查");
        }
        return s;
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
        if (survey.getClosedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "調查已結束，無法再填寫");
        }
        String name = body.getOrDefault("participantName", "").trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請選擇參與者姓名");
        }
        if (!survey.getParticipants().contains(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此姓名不在受調查人員名單中");
        }
        boolean isNew = responseRepo.findBySurveyIdAndParticipantName(id, name).isEmpty();
        SurveyResponse resp = responseRepo.findBySurveyIdAndParticipantName(id, name)
                .orElseGet(() -> {
                    SurveyResponse r = new SurveyResponse();
                    r.setSurveyId(id);
                    r.setParticipantName(name);
                    return r;
                });
        resp.setSlots(body.getOrDefault("slots", ""));
        resp.setUpdatedAt(LocalDateTime.now());
        SurveyResponse saved = responseRepo.save(resp);

        // 第一次填寫且全員到齊時，透過 WebSocket 即時通知發起者
        if (isNew && survey.getOwnerToken() != null) {
            long done = responseRepo.findBySurveyId(id).stream()
                    .map(SurveyResponse::getParticipantName)
                    .filter(survey.getParticipants()::contains)
                    .distinct().count();
            if (done >= survey.getParticipants().size()) {
                try {
                    String json = objectMapper.writeValueAsString(Map.of(
                            "type", "surveyComplete",
                            "surveyId", survey.getId(),
                            "name", survey.getName(),
                            "total", survey.getParticipants().size()));
                    notifier.notifyOwner(survey.getOwnerToken(), json);
                } catch (Exception ignored) {
                }
            }
        }
        return saved;
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
