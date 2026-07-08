package com.angus.timesurvey.job;

import com.angus.timesurvey.repo.SurveyRepository;
import com.angus.timesurvey.repo.SurveyResponseRepository;
import com.angus.timesurvey.repo.SurveyVisitRepository;
import com.angus.timesurvey.repo.UserActivityRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 驗證使用紀錄的保留機制：僅每月 1 號清除，截止日 = 當日往前推 stats.retention-months 個月 */
class HousekeepingJobTest {

    private final UserActivityRepository activityRepo = mock(UserActivityRepository.class);

    private HousekeepingJob job(int retentionMonths) {
        return new HousekeepingJob(mock(SurveyRepository.class), mock(SurveyResponseRepository.class),
                mock(SurveyVisitRepository.class), activityRepo, retentionMonths);
    }

    @Test
    void 每月1號清除超過保留月數的使用紀錄() {
        job(6).cleanupOldActivities(LocalDate.of(2026, 7, 1));
        verify(activityRepo).deleteByOccurredAtBefore(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void 非1號不清除使用紀錄() {
        job(6).cleanupOldActivities(LocalDate.of(2026, 7, 2));
        job(6).cleanupOldActivities(LocalDate.of(2026, 7, 31));
        verify(activityRepo, never()).deleteByOccurredAtBefore(any());
    }

    @Test
    void 保留月數依設定值計算截止日() {
        job(3).cleanupOldActivities(LocalDate.of(2026, 7, 1));
        verify(activityRepo).deleteByOccurredAtBefore(LocalDateTime.of(2026, 4, 1, 0, 0));
    }
}
