package com.angus.timesurvey.job;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Housekeeping 背景批次：清除過期調查。
 * 啟動時間由 application.properties 的 housekeeping.cron 以 crontab 格式設定（預設每晚 20:00）。
 * 以批次啟動日計算，調查迄日已經過 7 日者，連同填寫資料一併刪除。
 */
@Component
public class HousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingJob.class);

    private final SurveyRepository surveyRepo;
    private final SurveyResponseRepository responseRepo;
    private final SurveyVisitRepository visitRepo;

    public HousekeepingJob(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo,
                           SurveyVisitRepository visitRepo) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
        this.visitRepo = visitRepo;
    }

    @Scheduled(cron = "${housekeeping.cron:0 0 20 * * *}")
    @Transactional
    public void cleanupExpiredSurveys() {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(7);   // 迄日 <= 今日-7 即「已經過 7 日」
        List<Survey> expired = surveyRepo.findByEndDateLessThanEqual(cutoff);
        log.info("Housekeeping 開始：批次日 {}，清除迄日在 {}（含）之前的調查，共 {} 筆", today, cutoff, expired.size());
        for (Survey s : expired) {
            responseRepo.deleteBySurveyId(s.getId());
            visitRepo.deleteBySurveyId(s.getId());
            surveyRepo.delete(s);
            log.info("已清除過期調查：{}（{} ~ {}）", s.getName(), s.getStartDate(), s.getEndDate());
        }
        log.info("Housekeeping 結束");
    }
}
