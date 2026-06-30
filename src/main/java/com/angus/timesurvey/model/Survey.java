package com.angus.timesurvey.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

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

    /** 每日可調查的開始時間 */
    @Column(nullable = false)
    private LocalTime startTime;

    /** 每日可調查的結束時間 */
    @Column(nullable = false)
    private LocalTime endTime;

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
}
