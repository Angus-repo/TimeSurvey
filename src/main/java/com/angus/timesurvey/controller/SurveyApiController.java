package com.angus.timesurvey.controller;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.model.SurveyResponse;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import com.angus.timesurvey.ws.NotifyWebSocketHandler;
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
    private final SurveyVisitRepository visitRepo;
    private final NotifyWebSocketHandler notifier;
    private final ObjectMapper objectMapper;

    public SurveyApiController(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo,
                               SurveyVisitRepository visitRepo,
                               NotifyWebSocketHandler notifier, ObjectMapper objectMapper) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
        this.visitRepo = visitRepo;
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
        if (existing.getClosedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "調查已結束，無法編輯");
        }
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

    /** 重新開啟調查：清除結束時間，參與者可再次填寫、發起者可再編輯 */
    @PostMapping("/{id}/reopen")
    public Survey reopen(@PathVariable String id,
                         @RequestHeader(value = "X-Owner-Token", required = false) String owner) {
        Survey s = getOwned(id, owner);
        if (s.getClosedAt() != null) {
            s.setClosedAt(null);
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
        visitRepo.deleteBySurveyId(id);
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

        // 每次填寫（含覆寫）都推播進度，讓後台清單即時更新；全員首次到齊時另發完成通知
        if (survey.getOwnerToken() != null) {
            long done = responseRepo.findBySurveyId(id).stream()
                    .map(SurveyResponse::getParticipantName)
                    .filter(survey.getParticipants()::contains)
                    .distinct().count();
            try {
                notifier.notifyOwner(survey.getOwnerToken(), objectMapper.writeValueAsString(Map.of(
                        "type", "responseUpdated",
                        "surveyId", survey.getId(),
                        "name", survey.getName(),
                        "participantName", name,
                        "done", done,
                        "total", survey.getParticipants().size())));
                if (isNew && done >= survey.getParticipants().size()) {
                    notifier.notifyOwner(survey.getOwnerToken(), objectMapper.writeValueAsString(Map.of(
                            "type", "surveyComplete",
                            "surveyId", survey.getId(),
                            "name", survey.getName(),
                            "total", survey.getParticipants().size())));
                }
            } catch (Exception ignored) {
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
