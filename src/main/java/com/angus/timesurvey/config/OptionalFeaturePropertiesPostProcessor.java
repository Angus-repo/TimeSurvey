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
 * 可選功能設定檔的共用載入機制：啟動時讀取 data/ 下的設定檔（已 gitignore，不進版控），
 * 由使用者自行決定是否啟用該功能。
 *
 * <ul>
 *   <li>設定檔不存在，或存在但 {@link #buildProperties(Properties)} 判定必要欄位未填
 *       （回傳 null）→ 視為未設定，不注入任何屬性。</li>
 *   <li>必要欄位有值 → 將組出的屬性注入環境變數供 API 讀取。</li>
 * </ul>
 *
 * 設定檔路徑可透過 {@link #fileProperty()} 指定的屬性覆寫（主要供測試使用）。
 */
abstract class OptionalFeaturePropertiesPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (env.getPropertySources().contains(propertySourceName())) {
            return;   // devtools restart 等情況下避免重複加入
        }

        Path file = Path.of(env.getProperty(fileProperty(), defaultFile()));
        if (Files.notExists(file)) {
            return;   // 未提供設定檔 → 視為未啟用此功能
        }

        try {
            Properties fileProps = new Properties();
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                fileProps.load(in);
            }
            Properties result = buildProperties(fileProps);
            if (result == null) {
                return;   // 必要欄位未填 → 視為未設定
            }
            env.getPropertySources().addFirst(new PropertiesPropertySource(propertySourceName(), result));
        } catch (IOException e) {
            throw new UncheckedIOException(loadFailureMessage() + file, e);
        }
    }

    /** 注入的 property source 名稱，也用來避免 devtools restart 時重複加入 */
    protected abstract String propertySourceName();

    /** 覆寫設定檔路徑用的屬性名稱（主要供測試使用） */
    protected abstract String fileProperty();

    /** 設定檔預設路徑 */
    protected abstract String defaultFile();

    /** 由設定檔內容組出要注入的屬性；必要欄位未填時回傳 null 表示不啟用 */
    protected abstract Properties buildProperties(Properties fileProps);

    /** 設定檔讀取失敗時的錯誤訊息前綴（後面會接檔案路徑） */
    protected abstract String loadFailureMessage();

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    @Override
    public int getOrder() {
        // 需晚於載入 application.properties 的 ConfigDataEnvironmentPostProcessor，
        // 才讀得到設定檔路徑的覆寫值（測試用）
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
