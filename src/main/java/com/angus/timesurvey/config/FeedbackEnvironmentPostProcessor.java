package com.angus.timesurvey.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 啟動時讀取意見回饋功能的設定檔（預設 {@code ./data/feedback.properties}，位於已
 * gitignore 的 data/ 內，不進版控），讓收件者與主旨可由使用者自行指定：
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
public class FeedbackEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String FILE_PROPERTY = "feedback.file";
    static final String DEFAULT_FILE = "./data/feedback.properties";
    static final String RECIPIENT_KEY = "feedback.recipient";
    static final String SUBJECT_KEY = "feedback.subject";
    static final String DEFAULT_SUBJECT = "[TimeSurvey] 意見回饋";
    private static final String PROPERTY_SOURCE_NAME = "feedbackConfig";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (env.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;   // devtools restart 等情況下避免重複加入
        }

        String filePath = env.getProperty(FILE_PROPERTY, DEFAULT_FILE);
        Path file = Path.of(filePath);
        if (Files.notExists(file)) {
            return;   // 未提供設定檔 → 視為未設定意見回饋功能
        }

        try {
            Properties fileProps = new Properties();
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                fileProps.load(in);
            }
            String recipient = trimToNull(fileProps.getProperty(RECIPIENT_KEY));
            if (recipient == null) {
                return;   // 沒有填收件者 → 視為未設定
            }
            String subject = trimToNull(fileProps.getProperty(SUBJECT_KEY));

            Properties result = new Properties();
            result.setProperty(RECIPIENT_KEY, recipient);
            result.setProperty(SUBJECT_KEY, subject != null ? subject : DEFAULT_SUBJECT);
            env.getPropertySources().addFirst(new PropertiesPropertySource(PROPERTY_SOURCE_NAME, result));
        } catch (IOException e) {
            throw new UncheckedIOException("讀取意見回饋設定檔失敗：" + file, e);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    @Override
    public int getOrder() {
        // 需晚於載入 application.properties 的 ConfigDataEnvironmentPostProcessor，
        // 才讀得到 feedback.file 覆寫值（測試用）
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
