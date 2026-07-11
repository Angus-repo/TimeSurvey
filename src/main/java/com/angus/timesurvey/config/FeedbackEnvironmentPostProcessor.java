package com.angus.timesurvey.config;

import java.util.Properties;

/**
 * 啟動時讀取意見回饋功能的設定檔（預設 {@code ./data/feedback.properties}），
 * 讓收件者與主旨可由使用者自行指定：
 *
 * <ul>
 *   <li>設定檔不存在，或存在但未填 {@code feedback.recipient} → 視為未設定，
 *       前端不顯示「意見回饋」按鈕。</li>
 *   <li>{@code feedback.recipient} 有值 → 注入環境變數供 API 讀取；
 *       {@code feedback.subject} 未填時採用預設主旨。</li>
 * </ul>
 *
 * 設定檔路徑可透過屬性 {@code feedback.file} 覆寫（主要供測試使用）。
 */
public class FeedbackEnvironmentPostProcessor extends OptionalFeaturePropertiesPostProcessor {

    static final String FILE_PROPERTY = "feedback.file";
    static final String DEFAULT_FILE = "./data/feedback.properties";
    static final String RECIPIENT_KEY = "feedback.recipient";
    static final String SUBJECT_KEY = "feedback.subject";
    static final String DEFAULT_SUBJECT = "[TimeSurvey] 意見回饋";

    @Override
    protected String propertySourceName() {
        return "feedbackConfig";
    }

    @Override
    protected String fileProperty() {
        return FILE_PROPERTY;
    }

    @Override
    protected String defaultFile() {
        return DEFAULT_FILE;
    }

    @Override
    protected Properties buildProperties(Properties fileProps) {
        String recipient = trimToNull(fileProps.getProperty(RECIPIENT_KEY));
        if (recipient == null) {
            return null;   // 沒有填收件者 → 視為未設定
        }
        String subject = trimToNull(fileProps.getProperty(SUBJECT_KEY));

        Properties result = new Properties();
        result.setProperty(RECIPIENT_KEY, recipient);
        result.setProperty(SUBJECT_KEY, subject != null ? subject : DEFAULT_SUBJECT);
        return result;
    }

    @Override
    protected String loadFailureMessage() {
        return "讀取意見回饋設定檔失敗：";
    }
}
