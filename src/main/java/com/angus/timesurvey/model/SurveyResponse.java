package com.angus.timesurvey.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一位參與者對一份調查的勾選結果 */
@Entity
@Table(name = "survey_response",
       uniqueConstraints = @UniqueConstraint(columnNames = {"survey_id", "participant_name"}))
public class SurveyResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false, length = 36)
    private String surveyId;

    @Column(name = "participant_name", nullable = false)
    private String participantName;

    /** 勾選的 30 分鐘時段，逗號分隔，格式 yyyy-MM-ddTHH:mm */
    @Column(length = 100000)
    private String slots;

    /** 不參加此會議的原因（有值表示此人已表明不參加，slots 必為空） */
    @Column(length = 500)
    private String declineReason;

    /** 調查期間完全沒有可出席時段的原因（請假、出差等，slots 必為空） */
    @Column(length = 500)
    private String noTimeReason;

    /** 完全沒有可出席時段時，建議改開會議的日期區間 */
    private LocalDate suggestedStartDate;
    private LocalDate suggestedEndDate;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSurveyId() { return surveyId; }
    public void setSurveyId(String surveyId) { this.surveyId = surveyId; }

    public String getParticipantName() { return participantName; }
    public void setParticipantName(String participantName) { this.participantName = participantName; }

    public String getSlots() { return slots; }
    public void setSlots(String slots) { this.slots = slots; }

    public String getDeclineReason() { return declineReason; }
    public void setDeclineReason(String declineReason) { this.declineReason = declineReason; }

    public String getNoTimeReason() { return noTimeReason; }
    public void setNoTimeReason(String noTimeReason) { this.noTimeReason = noTimeReason; }

    public LocalDate getSuggestedStartDate() { return suggestedStartDate; }
    public void setSuggestedStartDate(LocalDate suggestedStartDate) { this.suggestedStartDate = suggestedStartDate; }

    public LocalDate getSuggestedEndDate() { return suggestedEndDate; }
    public void setSuggestedEndDate(LocalDate suggestedEndDate) { this.suggestedEndDate = suggestedEndDate; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
