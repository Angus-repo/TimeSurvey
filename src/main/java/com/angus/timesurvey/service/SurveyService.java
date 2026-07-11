package com.angus.timesurvey.service;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.model.SurveyResponse;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

/** 調查的共用業務規則：已回覆人數的定義、刪除調查時的連鎖刪除 */
@Service
public class SurveyService {

    private final SurveyRepository surveyRepo;
    private final SurveyResponseRepository responseRepo;
    private final SurveyVisitRepository visitRepo;

    public SurveyService(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo,
                         SurveyVisitRepository visitRepo) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
        this.visitRepo = visitRepo;
    }

    /** 已回覆人數的統一定義：回覆者須仍在受調查人員名單內，同名只計一次 */
    public static long respondedCount(Survey survey, List<SurveyResponse> responses) {
        return responses.stream()
                .map(SurveyResponse::getParticipantName)
                .filter(survey.getParticipants()::contains)
                .distinct().count();
    }

    /** 刪除調查與其所有附屬資料（回覆、造訪紀錄）；新增附屬資料表時只需擴充這裡 */
    @Transactional
    public void deleteSurveyCascade(String surveyId) {
        responseRepo.deleteBySurveyId(surveyId);
        visitRepo.deleteBySurveyId(surveyId);
        surveyRepo.deleteById(surveyId);
    }
}
