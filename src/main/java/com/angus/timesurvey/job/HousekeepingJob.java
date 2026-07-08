package com.angus.timesurvey.job;

import com.angus.timesurvey.model.Survey;
import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import com.angus.timesurvey.repo.UserActivityRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Housekeeping 背景批次：清除過期調查與過舊的使用紀錄。
 * 啟動時間由 application.properties 的 housekeeping.cron 以 crontab 格式設定（預設每晚 20:00）。
 * 以批次啟動日計算，調查迄日已經過 7 日者，連同填寫資料一併刪除。
 * Entra ID 登入者的使用紀錄保留月數由 stats.retention-months 設定（預設 6 個月），
 * 每月 1 號的批次順帶清除超過保留月數的紀錄。
 */
@Component
public class HousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingJob.class);

    private final SurveyRepository surveyRepo;
    private final SurveyResponseRepository responseRepo;
    private final SurveyVisitRepository visitRepo;
    private final UserActivityRepository activityRepo;
    private final int statsRetentionMonths;

    public HousekeepingJob(SurveyRepository surveyRepo, SurveyResponseRepository responseRepo,
                           SurveyVisitRepository visitRepo, UserActivityRepository activityRepo,
                           @Value("${stats.retention-months:6}") int statsRetentionMonths) {
        this.surveyRepo = surveyRepo;
        this.responseRepo = responseRepo;
        this.visitRepo = visitRepo;
        this.activityRepo = activityRepo;
        this.statsRetentionMonths = statsRetentionMonths;
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
        cleanupOldActivities(today);
        log.info("Housekeeping 結束");
    }

    /** 每月 1 號清除超過保留月數（stats.retention-months）的使用紀錄 */
    void cleanupOldActivities(LocalDate today) {
        if (today.getDayOfMonth() != 1) {
            return;
        }
        LocalDate cutoff = today.minusMonths(statsRetentionMonths);
        activityRepo.deleteByOccurredAtBefore(cutoff.atStartOfDay());
        log.info("已清除 {}（不含）之前的使用紀錄（保留 {} 個月）", cutoff, statsRetentionMonths);
    }
}
