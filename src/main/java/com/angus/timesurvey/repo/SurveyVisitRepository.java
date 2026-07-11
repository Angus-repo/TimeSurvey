package com.angus.timesurvey.repo;

import com.angus.timesurvey.model.SurveyVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SurveyVisitRepository extends JpaRepository<SurveyVisit, Long> {

    long countBySurveyId(String surveyId);

    @Query("select count(distinct v.ip) from SurveyVisit v where v.surveyId = ?1")
    long countDistinctIpBySurveyId(String surveyId);

    /** 各調查的開啟次數（surveyId -> count），一次查完供統計頁使用 */
    @Query("select v.surveyId, count(v) from SurveyVisit v group by v.surveyId")
    List<Object[]> countGroupBySurveyId();

    /** 各調查的不重複來源 IP 數（surveyId -> count），一次查完供統計頁使用 */
    @Query("select v.surveyId, count(distinct v.ip) from SurveyVisit v group by v.surveyId")
    List<Object[]> countDistinctIpGroupBySurveyId();

    @Query("select count(distinct v.ip) from SurveyVisit v")
    long countDistinctIpAll();

    void deleteBySurveyId(String surveyId);
}
