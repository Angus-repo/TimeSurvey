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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/surveys")
public class SurveyApiController {

    /** 每日調查時間固定為 09:00~17:30，不由前端提供 */
    private static final LocalTime FIXED_START_TIME = LocalTime.of(9, 0);
    private static final LocalTime FIXED_END_TIME = LocalTime.of(17, 30);

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

    /**
     * 後台清單一次取得各調查的填寫狀況（surveyId -> { done, hasCommonSlot }）。
     * 取代前端對每筆調查各發一次 responses 請求的 N+1 行為，避免調查一多就卡頓。
     * hasCommonSlot：全員都已填寫時為 true/false（是否存在全員都有空的時段）；
     *                尚未全部填寫則為 null（無法判斷）。
     */
    @GetMapping("/response-counts")
    public Map<String, Map<String, Object>> responseCounts(@RequestHeader(value = "X-Owner-Token", required = false) String owner) {
        if (owner == null || owner.isBlank()) {
            return Map.of();
        }
        List<Survey> surveys = surveyRepo.findByOwnerTokenOrderByCreatedAtDesc(owner);
        if (surveys.isEmpty()) {
            return Map.of();
        }
        List<String> ids = surveys.stream().map(Survey::getId).toList();
        // 一次撈出所有相關回覆後在記憶體分組，避免逐筆查詢資料庫
        Map<String, Map<String, Set<String>>> slotsBySurvey = new HashMap<>();
        Map<String, Set<String>> declinedBySurvey = new HashMap<>();   // 已表明不參加的人，不列入共同時段計算
        for (SurveyResponse r : responseRepo.findBySurveyIdIn(ids)) {
            Set<String> slots = new HashSet<>();
            if (r.getSlots() != null && !r.getSlots().isBlank()) {
                for (String slot : r.getSlots().split(",")) {
                    if (!slot.isBlank()) slots.add(slot);
                }
            }
            slotsBySurvey.computeIfAbsent(r.getSurveyId(), k -> new HashMap<>())
                    .put(r.getParticipantName(), slots);
            if (r.getDeclineReason() != null && !r.getDeclineReason().isBlank()) {
                declinedBySurvey.computeIfAbsent(r.getSurveyId(), k -> new HashSet<>())
                        .add(r.getParticipantName());
            }
        }
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Survey s : surveys) {
            Map<String, Set<String>> slotsByParticipant = slotsBySurvey.getOrDefault(s.getId(), Map.of());
            Set<String> declined = declinedBySurvey.getOrDefault(s.getId(), Set.of());
            List<String> participants = s.getParticipants();
            long done = participants.stream().filter(slotsByParticipant::containsKey).count();
            Boolean hasCommonSlot = null;
            if (!participants.isEmpty() && done == participants.size()) {
                Set<String> common = null;
                for (String p : participants) {
                    if (declined.contains(p)) continue;   // 不參加者不影響其他人的共同時段
                    Set<String> slots = slotsByParticipant.get(p);
                    if (common == null) {
                        common = new HashSet<>(slots);
                    } else {
                        common.retainAll(slots);
                    }
                }
                // 全員都不參加時視為沒有共同時段
                hasCommonSlot = common != null && !common.isEmpty();
            }
            Map<String, Object> row = new HashMap<>();
            row.put("done", (int) done);
            row.put("hasCommonSlot", hasCommonSlot);
            result.put(s.getId(), row);
        }
        return result;
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
        // 每日時間固定 09:00~17:30，不由前端提供
        survey.setStartTime(FIXED_START_TIME);
        survey.setEndTime(FIXED_END_TIME);
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
        // 每日時間固定 09:00~17:30，不由前端提供
        existing.setStartTime(FIXED_START_TIME);
        existing.setEndTime(FIXED_END_TIME);
        existing.setParticipants(survey.getParticipants());
        existing.setExcludedDates(survey.getExcludedDates());
        existing.setAllowAddParticipant(survey.isAllowAddParticipant());
        existing.setAllowReplaceParticipant(survey.isAllowReplaceParticipant());
        return surveyRepo.save(existing);
    }

    /** 邀請他人加入：填寫頁的參與者自行新增其他需要參與會議的人（需 allowAddParticipant 開啟且調查未結束） */
    @PostMapping("/{id}/participants")
    public Survey addParticipant(@PathVariable String id, @RequestBody Map<String, String> body) {
        Survey survey = get(id);
        if (survey.getClosedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "調查已結束，無法再新增人員");
        }
        if (!survey.isAllowAddParticipant()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此調查不允許自行新增人員");
        }
        String name = body.getOrDefault("name", "").trim();
        String inviter = body.getOrDefault("inviter", "").trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請輸入姓名");
        }
        if (inviter.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請先選擇您的姓名，再邀請他人加入");
        }
        if (survey.getParticipants().contains(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此姓名已在受調查人員名單中：「" + name + "」已存在，無法重複邀請");
        }
        survey.getParticipants().add(name);
        survey.getParticipantNotes().put(name, "由 " + inviter + " 邀請加入");
        return surveyRepo.save(survey);
    }

    /** 換員：填寫頁的參與者把自己的名字換成其它還不在名單中的人，可一次換成多位（需 allowReplaceParticipant 開啟且調查未結束）。
     *  body 支援 newNames（字串陣列，換成多位）或 newName（單一字串，向下相容）；
     *  第一位頂替原本的位置並沿用「換員轉入」說明，其餘則視為一併加入的新人員。 */
    @PostMapping("/{id}/replace-participant")
    @Transactional
    public Survey replaceParticipant(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Survey survey = get(id);
        if (survey.getClosedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "調查已結束，無法再更換人員");
        }
        if (!survey.isAllowReplaceParticipant()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此調查不允許更換人員");
        }
        String oldName = String.valueOf(body.getOrDefault("oldName", "")).trim();
        if (oldName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請選擇要更換的姓名");
        }

        List<String> newNames = new ArrayList<>();
        Object rawNames = body.get("newNames");
        if (rawNames instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) newNames.add(o.toString().trim());
            }
        } else if (body.get("newName") != null) {
            String single = body.get("newName").toString().trim();
            if (!single.isEmpty()) newNames.add(single);
        }
        // 去除重複（保留第一次出現的順序）
        newNames = newNames.stream().distinct().toList();
        if (newNames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請輸入新的姓名");
        }

        int idx = survey.getParticipants().indexOf(oldName);
        if (idx < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此姓名不在受調查人員名單中");
        }
        for (String newName : newNames) {
            if (survey.getParticipants().contains(newName)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此姓名已在受調查人員名單中：「" + newName + "」已存在，無法換成同一人");
            }
        }

        survey.getParticipants().set(idx, newNames.get(0));
        survey.getParticipantNotes().remove(oldName);
        survey.getParticipantNotes().put(newNames.get(0), "由 " + oldName + " 換員轉入");
        for (int i = 1; i < newNames.size(); i++) {
            survey.getParticipants().add(newNames.get(i));
            survey.getParticipantNotes().put(newNames.get(i), "由 " + oldName + " 換員加入");
        }
        responseRepo.deleteBySurveyIdAndParticipantName(id, oldName);
        return surveyRepo.save(survey);
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
        // 三種互斥的回覆型態：不參加此會議 / 完全無可出席時段（附建議日期區間）/ 一般勾選時段
        String declineReason = body.getOrDefault("declineReason", "").trim();
        String noTimeReason = body.getOrDefault("noTimeReason", "").trim();
        resp.setDeclineReason(null);
        resp.setNoTimeReason(null);
        resp.setSuggestedStartDate(null);
        resp.setSuggestedEndDate(null);
        resp.setSuggestedExcludedDates(List.of());
        if (!declineReason.isEmpty()) {
            resp.setSlots("");
            resp.setDeclineReason(declineReason);
        } else if (!noTimeReason.isEmpty()) {
            LocalDate from = parseDate(body.get("suggestedStartDate"));
            LocalDate to = parseDate(body.get("suggestedEndDate"));
            List<LocalDate> excluded = parseDateList(body.get("suggestedExcludedDates"));
            if (from == null || to == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請提供建議的會議日期區間");
            }
            if (to.isBefore(from)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "建議的結束日期不可早於開始日期");
            }
            for (LocalDate d : excluded) {
                if (!d.isAfter(from) || !d.isBefore(to)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "建議挖空日期必須位於建議區間內");
                }
            }
            resp.setSlots("");
            resp.setNoTimeReason(noTimeReason);
            resp.setSuggestedStartDate(from);
            resp.setSuggestedEndDate(to);
            resp.setSuggestedExcludedDates(excluded);
        } else {
            resp.setSlots(body.getOrDefault("slots", ""));
        }
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
                            "total", survey.getParticipants().size(),
                            // 點通知後開啟此調查結果並停在該處的後台網址
                            "url", "/?result=" + survey.getId())));
                }
            } catch (Exception ignored) {
            }
        }
        return saved;
    }

    /** "yyyy-MM-dd" -> LocalDate；空值回傳 null，格式錯誤回 400 */
    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "建議日期格式不正確");
        }
    }

    /** 逗號分隔 yyyy-MM-dd -> LocalDate 清單；空值回傳空清單 */
    private List<LocalDate> parseDateList(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<LocalDate> out = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        for (String part : s.split(",")) {
            LocalDate d = parseDate(part);
            if (d != null && seen.add(d)) out.add(d);
        }
        return out;
    }

    private void validate(Survey s) {
        if (s.getName() == null || s.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請輸入調查名稱");
        }
        if (s.getStartDate() == null || s.getEndDate() == null || s.getEndDate().isBefore(s.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期範圍不正確");
        }
        // 起迄日期跨度不得超過一個月（例如 6/30~7/30 為上限）
        if (s.getEndDate().isAfter(s.getStartDate().plusMonths(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期範圍不可超過一個月");
        }
        // 被挖空（排除）的日期必須落在起迄範圍內，且不可把整段都排除掉
        List<LocalDate> excluded = s.getExcludedDates() == null ? List.of() : s.getExcludedDates();
        for (LocalDate ex : excluded) {
            if (ex.isBefore(s.getStartDate()) || ex.isAfter(s.getEndDate())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排除的日期超出調查範圍");
            }
        }
        Set<LocalDate> exSet = new HashSet<>(excluded);
        boolean anyLeft = false;
        for (LocalDate d = s.getStartDate(); !d.isAfter(s.getEndDate()); d = d.plusDays(1)) {
            if (!exSet.contains(d)) { anyLeft = true; break; }
        }
        if (!anyLeft) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請至少保留一個調查日期");
        }
        if (s.getStartTime() == null || s.getEndTime() == null || !s.getEndTime().isAfter(s.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "時間範圍不正確");
        }
        if (s.getParticipants() == null || s.getParticipants().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請至少輸入一位受調查人員");
        }
        // 人數下限 2：太少湊不成會議。超過 30 人不阻擋，僅由前端提示（人數過多較難喬出共同時間）
        if (s.getParticipants().size() < 2) {
            // throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "受調查人員至少需要兩位");
        }
    }
}
