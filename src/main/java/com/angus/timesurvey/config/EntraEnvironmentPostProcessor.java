package com.angus.timesurvey.config;

import java.util.Properties;

/**
 * 啟動時讀取 Microsoft Entra ID（Azure AD）登入功能的設定檔
 * （預設 {@code ./data/entra.properties}），讓使用者自行選擇是否啟用 Entra ID 登入：
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
public class EntraEnvironmentPostProcessor extends OptionalFeaturePropertiesPostProcessor {

    static final String FILE_PROPERTY = "entra.file";
    static final String DEFAULT_FILE = "./data/entra.properties";
    static final String CLIENT_ID_KEY = "entra.client-id";
    static final String CLIENT_SECRET_KEY = "entra.client-secret";
    static final String TENANT_ID_KEY = "entra.tenant-id";
    static final String DEFAULT_TENANT = "common";

    @Override
    protected String propertySourceName() {
        return "entraConfig";
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
        String clientId = trimToNull(fileProps.getProperty(CLIENT_ID_KEY));
        if (clientId == null) {
            return null;   // 沒有填 CLIENT_ID → 視為未啟用
        }
        String tenantId = trimToNull(fileProps.getProperty(TENANT_ID_KEY));
        String clientSecret = trimToNull(fileProps.getProperty(CLIENT_SECRET_KEY));

        Properties result = new Properties();
        result.setProperty(CLIENT_ID_KEY, clientId);
        result.setProperty(TENANT_ID_KEY, tenantId != null ? tenantId : DEFAULT_TENANT);
        if (clientSecret != null) {
            result.setProperty(CLIENT_SECRET_KEY, clientSecret);
        }
        return result;
    }

    @Override
    protected String loadFailureMessage() {
        return "讀取 Entra ID 設定檔失敗：";
    }
}
