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
 * 啟動時讀取 Microsoft Entra ID（Azure AD）登入功能的設定檔
 * （預設 {@code ./data/entra.properties}，位於已 gitignore 的 data/ 內，不進版控），
 * 讓使用者自行選擇是否啟用 Entra ID 登入：
 *
 * <ul>
 *   <li>設定檔不存在，或存在但未填 {@code entra.client-id} → 視為未設定，
 *       各網頁（index.html / survey.html / stats.html）不啟用 Entra ID 登入。</li>
 *   <li>{@code entra.client-id} 有值 → 注入環境變數供 API 讀取；
 *       {@code entra.tenant-id} 未填時採用預設值 {@code common}（公司／學校帳號與個人
 *       Microsoft 帳戶皆可登入）。注意：若填 {@code organizations} 會擋掉個人帳戶——
 *       個人帳戶在微軟登入頁選了帳號會被直接退回帳戶選擇頁、形成無限循環。</li>
 *   <li>{@code entra.client-secret}：後端以授權碼流程換取並保存 refresh token 所需的
 *       用戶端密碼。登入採後端流程，client-id 與 client-secret 都有值才會啟用。</li>
 * </ul>
 *
 * 設定檔路徑可透過屬性 {@code entra.file} 覆寫（主要供測試使用）。
 */
public class EntraEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String FILE_PROPERTY = "entra.file";
    static final String DEFAULT_FILE = "./data/entra.properties";
    static final String CLIENT_ID_KEY = "entra.client-id";
    static final String CLIENT_SECRET_KEY = "entra.client-secret";
    static final String TENANT_ID_KEY = "entra.tenant-id";
    static final String DEFAULT_TENANT = "common";
    private static final String PROPERTY_SOURCE_NAME = "entraConfig";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (env.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;   // devtools restart 等情況下避免重複加入
        }

        String filePath = env.getProperty(FILE_PROPERTY, DEFAULT_FILE);
        Path file = Path.of(filePath);
        if (Files.notExists(file)) {
            return;   // 未提供設定檔 → 視為未啟用 Entra ID 登入
        }

        try {
            Properties fileProps = new Properties();
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                fileProps.load(in);
            }
            String clientId = trimToNull(fileProps.getProperty(CLIENT_ID_KEY));
            if (clientId == null) {
                return;   // 沒有填 CLIENT_ID → 視為未啟用
            }
            String tenantId = trimToNull(fileProps.getProperty(TENANT_ID_KEY));
            String clientSecret = trimToNull(fileProps.getProperty(CLIENT_SECRET_KEY));

            Properties result = new Properties();
            result.setProperty(CLIENT_ID_KEY, clientId);
            result.setProperty(TENANT_ID_KEY, tenantId != null ? tenantId : DEFAULT_TENANT);
            if (clientSecret != null) {
                result.setProperty(CLIENT_SECRET_KEY, clientSecret);
            }
            env.getPropertySources().addFirst(new PropertiesPropertySource(PROPERTY_SOURCE_NAME, result));
        } catch (IOException e) {
            throw new UncheckedIOException("讀取 Entra ID 設定檔失敗：" + file, e);
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
        // 才讀得到 entra.file 覆寫值（測試用）
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
