package com.angus.timesurvey.config;

import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * 啟動時管理 H2 file DB 的 sa 密碼，讓密碼能動態產生、加密儲存，且不進 git 版控：
 *
 * <ul>
 *   <li>首次（資料庫檔與密碼檔都不存在）→ 產生強隨機密碼，用 Jasypt 加密成 {@code ENC(...)}
 *       後寫入資料庫旁的 {@code db-secret.properties}（位於已 gitignore 的 data/ 內）。</li>
 *   <li>之後每次啟動 → 載入該密碼檔；其中的 {@code ENC(...)} 由 jasypt-spring-boot 於讀取
 *       {@code spring.datasource.password} 時，用主金鑰自動在記憶體解密。</li>
 *   <li>資料庫檔已存在但密碼檔遺失 → 直接報錯停機（原密碼已無法復原）。</li>
 * </ul>
 *
 * 只在 {@code spring.datasource.url} 為 {@code jdbc:h2:file:} 時作用；
 * 測試用的 in-memory（{@code jdbc:h2:mem:}）資料庫不受影響。
 * 主金鑰優先取環境變數 {@code JASYPT_ENCRYPTOR_PASSWORD}（對應屬性 {@code jasypt.encryptor.password}）；
 * 未提供時改用主機名稱（hostname）作為預設主金鑰，並回填給 jasypt-spring-boot，
 * 確保「產生密碼」與「啟動解密」用的是同一把金鑰。
 */
public class DbPasswordEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String FILE_URL_PREFIX = "jdbc:h2:file:";
    private static final String PWD_KEY = "spring.datasource.password";
    private static final String MASTER_KEY = "jasypt.encryptor.password";
    private static final String PROPERTY_SOURCE_NAME = "dbSecret";
    private static final String SECRET_FILE_NAME = "db-secret.properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String url = env.getProperty("spring.datasource.url", "");
        if (!url.startsWith(FILE_URL_PREFIX)) {
            return;   // 只處理檔案型 H2；in-memory（測試）略過
        }
        if (env.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;   // devtools restart 等情況下避免重複加入
        }

        // 主金鑰：優先用傳入的 jasypt.encryptor.password / JASYPT_ENCRYPTOR_PASSWORD，
        // 未提供時以主機名稱（hostname）作為預設值
        String provided = env.getProperty(MASTER_KEY);
        boolean usingDefault = isBlank(provided);
        String masterKey = usingDefault ? defaultHostname() : provided;

        String base = url.substring(FILE_URL_PREFIX.length());
        int sep = base.indexOf(';');
        if (sep >= 0) {
            base = base.substring(0, sep);   // 去掉 ;AUTO_SERVER=TRUE 等參數
        }
        Path dbFile = Path.of(base + ".mv.db");
        Path secretFile = Path.of(base).resolveSibling(SECRET_FILE_NAME);

        try {
            if (Files.notExists(secretFile)) {
                if (Files.exists(dbFile)) {
                    throw new IllegalStateException(
                            "找到資料庫 " + dbFile + " 但密碼檔 " + secretFile + " 遺失，無法取得原密碼。"
                          + "若要重新開始，請刪除整個 data/ 目錄後再啟動。");
                }
                generateSecretFile(secretFile, masterKey);
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(secretFile)) {
                props.load(in);
            }
            // 使用者沒提供主金鑰時，把預設（hostname）回填給 jasypt-spring-boot，
            // 讓它解密 spring.datasource.password 的 ENC(...) 時用同一把金鑰
            if (usingDefault) {
                props.setProperty(MASTER_KEY, masterKey);
            }
            // spring.datasource.password 是 ENC(...) 密文，稍後由 jasypt-spring-boot 讀取時自動解密
            env.getPropertySources().addFirst(new PropertiesPropertySource(PROPERTY_SOURCE_NAME, props));
        } catch (IOException e) {
            throw new UncheckedIOException("讀寫資料庫密碼檔失敗：" + secretFile, e);
        }
    }

    private void generateSecretFile(Path secretFile, String masterKey) throws IOException {
        String cipher = encrypt(masterKey, randomPassword());
        if (secretFile.getParent() != null) {
            Files.createDirectories(secretFile.getParent());
        }
        String content =
                "# 本檔由系統於首次建立資料庫時自動產生，內含 H2 sa 的加密密碼（ENC 為 Jasypt 密文）。\n"
              + "# 請勿提交進 git，也不要手動修改；要整組重置請刪除整個 data/ 目錄（資料與密碼一起）。\n"
              + PWD_KEY + "=ENC(" + cipher + ")\n";
        Files.writeString(secretFile, content);
    }

    /** 主機名稱作為主金鑰預設值；取不到時退回固定字串，避免空金鑰。 */
    static String defaultHostname() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            if (!isBlank(h)) {
                return h;
            }
        } catch (Exception ignored) {
            // 落到下面的環境變數 / 保底值
        }
        String env = System.getenv("HOSTNAME");
        if (isBlank(env)) {
            env = System.getenv("COMPUTERNAME");
        }
        return isBlank(env) ? "timesurvey-default-key" : env;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 參數與 jasypt-spring-boot 3.x 預設一致，產出的 ENC(...) 才能被其自動解密。 */
    private String encrypt(String masterKey, String plain) {
        PooledPBEStringEncryptor enc = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig c = new SimpleStringPBEConfig();
        c.setPassword(masterKey);
        c.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        c.setKeyObtentionIterations("1000");
        c.setPoolSize("1");
        c.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        c.setIvGeneratorClassName("org.jasypt.iv.RandomIvGenerator");
        c.setStringOutputType("base64");
        enc.setConfig(c);
        return enc.encrypt(plain);
    }

    private String randomPassword() {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @Override
    public int getOrder() {
        // 必須晚於載入 application.properties 的 ConfigDataEnvironmentPostProcessor，
        // 才讀得到 spring.datasource.url 來判斷是否為檔案型資料庫
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
