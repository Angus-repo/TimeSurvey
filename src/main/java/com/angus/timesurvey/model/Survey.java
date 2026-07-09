package com.angus.timesurvey.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "survey")
public class Survey {

    /** 以 UUID 字串當主鍵，同時作為調查連結的識別碼 */
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** 每日可調查的開始時間（固定 09:00，不由前端提供） */
    @Column(nullable = false)
    private LocalTime startTime = LocalTime.of(9, 0);

    /** 每日可調查的結束時間（固定 17:30，不由前端提供） */
    @Column(nullable = false)
    private LocalTime endTime = LocalTime.of(17, 30);

    /** 發起者（邀請者）的 IANA 時區（例如 Asia/Taipei），由瀏覽器於建立時帶入；
     *  調查時段一律以此時區為準，填寫者時區不同時填寫頁會提示。舊資料為 null */
    @Column(length = 64)
    private String timeZone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "survey_participant", joinColumns = @JoinColumn(name = "survey_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "participant_name")
    private List<String> participants = new ArrayList<>();

    /** 起迄範圍內被「挖空」、不列入調查的日期（空集合代表整段都調查） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "survey_excluded_date", joinColumns = @JoinColumn(name = "survey_id"))
    @Column(name = "excluded_date")
    private List<LocalDate> excludedDates = new ArrayList<>();

    private LocalDateTime createdAt;

    /** 結束調查時間，非 null 表示已結束、參與者不能再填寫 */
    private LocalDateTime closedAt;

    /** 發起者識別碼（瀏覽器產生），只寫入不回傳，後台僅能看到自己發起的調查 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 64)
    private String ownerToken;

    /** 是否允許填寫者在填寫頁自行邀請其他需要參與會議的人加入受調查名單
     *  columnDefinition 帶預設值，讓既有資料庫在新增此欄位時（ALTER TABLE ADD COLUMN）
     *  既有資料列可取得預設值，避免 NOT NULL 但無預設值導致的欄位新增失敗 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean allowAddParticipant = false;

    /** 是否允許填寫者將自己的名字換成其它還不在名單中的人（同上，帶預設值避免既有資料庫升級失敗） */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean allowReplaceParticipant = false;

    /** 記錄透過「邀請他人加入」或「換員」新增的參與者是由誰邀請／換成的（key＝參與者姓名，value＝說明文字）。
     *  發起者自行輸入的人員不會有紀錄；此 map 只用於顯示來源說明，非必要欄位。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "survey_participant_note", joinColumns = @JoinColumn(name = "survey_id"))
    @MapKeyColumn(name = "participant_name")
    @Column(name = "note", length = 200)
    private Map<String, String> participantNotes = new HashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

    public List<String> getParticipants() { return participants; }
    public void setParticipants(List<String> participants) { this.participants = participants; }

    public List<LocalDate> getExcludedDates() { return excludedDates; }
    public void setExcludedDates(List<LocalDate> excludedDates) {
        this.excludedDates = excludedDates == null ? new ArrayList<>() : excludedDates;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public String getOwnerToken() { return ownerToken; }
    public void setOwnerToken(String ownerToken) { this.ownerToken = ownerToken; }

    public boolean isAllowAddParticipant() { return allowAddParticipant; }
    public void setAllowAddParticipant(boolean allowAddParticipant) { this.allowAddParticipant = allowAddParticipant; }

    public boolean isAllowReplaceParticipant() { return allowReplaceParticipant; }
    public void setAllowReplaceParticipant(boolean allowReplaceParticipant) { this.allowReplaceParticipant = allowReplaceParticipant; }

    public Map<String, String> getParticipantNotes() { return participantNotes; }
    public void setParticipantNotes(Map<String, String> participantNotes) {
        this.participantNotes = participantNotes == null ? new HashMap<>() : participantNotes;
    }
}
