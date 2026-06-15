package com.angus.timesurvey.config;

import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 驗證資料庫密碼的「動態產生、加密儲存、跨重啟沿用」行為。 */
class DbPasswordEnvironmentPostProcessorTest {

    private static final String MASTER = "test-master-key";

    private StandardEnvironment fileDbEnv(Path dir, boolean withMasterKey) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> m = new HashMap<>();
        m.put("spring.datasource.url", "jdbc:h2:file:" + dir.resolve("timesurvey") + ";AUTO_SERVER=TRUE");
        if (withMasterKey) {
            m.put("jasypt.encryptor.password", MASTER);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", m));
        return env;
    }

    private void run(StandardEnvironment env) {
        new DbPasswordEnvironmentPostProcessor().postProcessEnvironment(env, null);
    }

    private String readEnc(Path dir) throws Exception {
        Properties p = new Properties();
        try (var in = Files.newInputStream(dir.resolve("db-secret.properties"))) {
            p.load(in);
        }
        return p.getProperty("spring.datasource.password");
    }

    /** 用與 jasypt-spring-boot 3.x 相同的參數解開 ENC(...)，證明執行期 Jasypt 解得開。 */
    private String decrypt(String key, String enc) {
        PooledPBEStringEncryptor e = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig c = new SimpleStringPBEConfig();
        c.setPassword(key);
        c.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        c.setKeyObtentionIterations("1000");
        c.setPoolSize("1");
        c.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        c.setIvGeneratorClassName("org.jasypt.iv.RandomIvGenerator");
        c.setStringOutputType("base64");
        e.setConfig(c);
        return e.decrypt(enc.substring("ENC(".length(), enc.length() - 1));
    }

    @Test
    void 首次啟動產生加密密碼檔並可解回隨機密碼(@TempDir Path dir) throws Exception {
        StandardEnvironment env = fileDbEnv(dir, true);
        run(env);

        String enc = readEnc(dir);
        assertNotNull(enc, "應寫出密碼");
        assertTrue(enc.startsWith("ENC(") && enc.endsWith(")"), "應為 ENC(...) 密文");
        assertEquals(32, decrypt(MASTER, enc).length(), "解密後應為 32 字元隨機密碼");
        assertEquals(enc, env.getProperty("spring.datasource.password"), "應已注入密文供 Jasypt 解密");
    }

    @Test
    void 再次啟動沿用同一密碼不覆寫(@TempDir Path dir) throws Exception {
        run(fileDbEnv(dir, true));
        String first = readEnc(dir);
        Files.writeString(dir.resolve("timesurvey.mv.db"), "x");   // 模擬資料庫已建立

        run(fileDbEnv(dir, true));
        assertEquals(first, readEnc(dir), "既有密碼檔不應被覆寫");
    }

    @Test
    void 資料庫存在但密碼檔遺失應報錯(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("timesurvey.mv.db"), "x");   // 有 DB、無密碼檔
        assertThrows(IllegalStateException.class, () -> run(fileDbEnv(dir, true)));
    }

    @Test
    void 未提供主金鑰時改用hostname並可自解(@TempDir Path dir) throws Exception {
        StandardEnvironment env = fileDbEnv(dir, false);   // 不提供主金鑰
        run(env);

        String key = env.getProperty("jasypt.encryptor.password");
        assertNotNull(key, "應回填預設主金鑰供 jasypt 解密");
        assertTrue(!key.isBlank());
        // 用被回填的預設金鑰解開密碼檔，證明產生與解密用的是同一把（hostname）金鑰
        assertEquals(32, decrypt(key, readEnc(dir)).length(), "產生與解密應用同一把預設金鑰");
    }

    @Test
    void 預設主金鑰為主機名稱() throws Exception {
        assertEquals(java.net.InetAddress.getLocalHost().getHostName(),
                DbPasswordEnvironmentPostProcessor.defaultHostname());
    }

    @Test
    void 記憶體資料庫不介入(@TempDir Path dir) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> m = new HashMap<>();
        m.put("spring.datasource.url", "jdbc:h2:mem:bddtest");
        env.getPropertySources().addFirst(new MapPropertySource("test", m));
        run(env);
        assertNull(env.getProperty("spring.datasource.password"), "in-memory 不應注入密碼");
        assertTrue(Files.notExists(dir.resolve("db-secret.properties")), "in-memory 不應產生密碼檔");
    }
}
