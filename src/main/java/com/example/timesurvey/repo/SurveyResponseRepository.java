package com.example.timesurvey.repo;

import com.example.timesurvey.model.SurveyResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, Long> {
    List<SurveyResponse> findBySurveyId(String surveyId);
    Optional<SurveyResponse> findBySurveyIdAndParticipantName(String surveyId, String participantName);
    void deleteBySurveyId(String surveyId);
}
