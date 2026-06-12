package com.angus.timesurvey.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 調查填寫頁的一次開啟紀錄（含來源 IP），供後台統計使用 */
@Entity
@Table(name = "survey_visit")
public class SurveyVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false, length = 36)
    private String surveyId;

    @Column(length = 64)
    private String ip;

    private LocalDateTime visitedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSurveyId() { return surveyId; }
    public void setSurveyId(String surveyId) { this.surveyId = surveyId; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public LocalDateTime getVisitedAt() { return visitedAt; }
    public void setVisitedAt(LocalDateTime visitedAt) { this.visitedAt = visitedAt; }
}
