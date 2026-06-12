package com.angus.timesurvey.repo;

import com.angus.timesurvey.model.SurveyVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SurveyVisitRepository extends JpaRepository<SurveyVisit, Long> {

    long countBySurveyId(String surveyId);

    @Query("select count(distinct v.ip) from SurveyVisit v where v.surveyId = ?1")
    long countDistinctIpBySurveyId(String surveyId);

    @Query("select count(distinct v.ip) from SurveyVisit v")
    long countDistinctIpAll();

    void deleteBySurveyId(String surveyId);
}
