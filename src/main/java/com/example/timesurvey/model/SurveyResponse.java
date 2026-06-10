package com.example.timesurvey.model;

import jakarta.persistence.*;

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

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSurveyId() { return surveyId; }
    public void setSurveyId(String surveyId) { this.surveyId = surveyId; }

    public String getParticipantName() { return participantName; }
    public void setParticipantName(String participantName) { this.participantName = participantName; }

    public String getSlots() { return slots; }
    public void setSlots(String slots) { this.slots = slots; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
