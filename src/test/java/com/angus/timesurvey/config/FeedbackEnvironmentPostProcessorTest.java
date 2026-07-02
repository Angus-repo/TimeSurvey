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

/** 驗證意見回饋設定檔的讀取行為：未設定則不注入，已設定則注入（含主旨預設值）。 */
class FeedbackEnvironmentPostProcessorTest {

    private StandardEnvironment envWithFile(String filePath) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> m = new HashMap<>();
        if (filePath != null) {
            m.put("feedback.file", filePath);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", m));
        return env;
    }

    private void run(StandardEnvironment env) {
        new FeedbackEnvironmentPostProcessor().postProcessEnvironment(env, null);
    }

    @Test
    void 設定檔不存在時不注入(@TempDir Path dir) {
        StandardEnvironment env = envWithFile(dir.resolve("nope.properties").toString());
        run(env);
        assertNull(env.getProperty("feedback.recipient"));
        assertNull(env.getProperty("feedback.subject"));
    }

    @Test
    void 有設定檔但未填收件者時不注入(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feedback.properties");
        Files.writeString(f, "feedback.subject=主旨測試\n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertNull(env.getProperty("feedback.recipient"));
    }

    @Test
    void 只填收件者時採用預設主旨(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feedback.properties");
        Files.writeString(f, "feedback.recipient=a@b.com\n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertEquals("a@b.com", env.getProperty("feedback.recipient"));
        assertEquals("[TimeSurvey] 意見回饋", env.getProperty("feedback.subject"));
    }

    @Test
    void 填了收件者與自訂主旨時採用檔案內容(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("feedback.properties");
        Files.writeString(f, "feedback.recipient=a@b.com\nfeedback.subject=自訂主旨\n");
        StandardEnvironment env = envWithFile(f.toString());
        run(env);
        assertEquals("a@b.com", env.getProperty("feedback.recipient"));
        assertEquals("自訂主旨", env.getProperty("feedback.subject"));
    }

    @Test
    void 預設路徑為資料目錄下的feedback檔案() {
        assertEquals("./data/feedback.properties", FeedbackEnvironmentPostProcessor.DEFAULT_FILE);
    }
}
