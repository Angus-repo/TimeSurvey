package com.angus.timesurvey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 驗證 Entra ID 設定檔的讀取行為：未設定則不注入，已設定則注入（含租戶預設值）。 */
class EntraEnvironmentPostProcessorTest {

    private StandardEnvironment envWithFile(String filePath) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> m = new HashMap<>();
        if (filePath != null) {
            m.put("entra.file", filePath);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", m));
        return env;
    }

    private void run(StandardEnvironment env) {
        new EntraEnvironmentPostProcessor().postProcessEnvironment(env, null);
    }

    @Test
    void 設定檔不存在時不注入(@TempDir Path dir) {
        StandardEnvironment env = envWithFile(dir.resolve("nope.properties").toString());
        run(env);
        assertNull(env.getProperty("entra.client-id"));
        assertNull(env.getProperty("entra.tenant-id"));
    }

    @Test
    void 有設定檔但未填CLIENT_ID時不注入(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("entra.properties");
        Files.writeString(f, "entra.tenant-id=my-tenant\n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertNull(env.getProperty("entra.client-id"));
    }

    @Test
    void CLIENT_ID為空白字元時仍視為未設定(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("entra.properties");
        Files.writeString(f, "entra.client-id=   \n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertNull(env.getProperty("entra.client-id"));
    }

    @Test
    void 只填CLIENT_ID時租戶採用預設值(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("entra.properties");
        Files.writeString(f, "entra.client-id=11111111-2222-3333-4444-555555555555\n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertEquals("11111111-2222-3333-4444-555555555555", env.getProperty("entra.client-id"));
        // 預設 common：公司帳號與個人 Microsoft 帳戶皆可登入（organizations 會擋掉個人帳戶）
        assertEquals("common", env.getProperty("entra.tenant-id"));
    }

    @Test
    void 填了CLIENT_ID與租戶時採用檔案內容(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("entra.properties");
        Files.writeString(f, "entra.client-id=my-client\nentra.tenant-id=my-tenant\n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertEquals("my-client", env.getProperty("entra.client-id"));
        assertEquals("my-tenant", env.getProperty("entra.tenant-id"));
    }

    @Test
    void 填了CLIENT_SECRET時一併注入(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("entra.properties");
        Files.writeString(f, "entra.client-id=my-client\nentra.client-secret=my-secret\n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertEquals("my-client", env.getProperty("entra.client-id"));
        assertEquals("my-secret", env.getProperty("entra.client-secret"));
    }

    @Test
    void 未填CLIENT_SECRET時不注入該屬性(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("entra.properties");
        Files.writeString(f, "entra.client-id=my-client\nentra.client-secret=   \n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertNull(env.getProperty("entra.client-secret"));
    }

    @Test
    void 預設路徑為資料目錄下的entra檔案() {
        assertEquals("./data/entra.properties", EntraEnvironmentPostProcessor.DEFAULT_FILE);
    }
}
