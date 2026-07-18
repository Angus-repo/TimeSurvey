package com.angus.timesurvey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 驗證憑證方式的應用程式身分：載入 PEM 憑證與 PKCS#8 私鑰、
 * 簽發的 client assertion 內容（x5t#S256、aud、iss/sub、效期）與簽章可用憑證公鑰驗證；
 * 私鑰為 PKCS#1 舊格式或檔案缺漏時給出可行動的錯誤訊息。
 */
class EntraClientCertificateTest {

    private static final String CERT = "src/test/resources/entra/test-cert.pem";
    private static final String KEY = "src/test/resources/entra/test-key.pem";
    private static final String KEY_PKCS1 = "src/test/resources/entra/test-key-pkcs1.pem";
    private static final String CERT_WITH_KEY = "src/test/resources/entra/test-cert-with-key.pem";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 未設定憑證路徑時回傳null() {
        assertNull(EntraClientCertificate.loadOrNull(null, null));
        assertNull(EntraClientCertificate.loadOrNull("  ", null));
    }

    @Test
    void 憑證與私鑰分開兩檔時可載入並簽發assertion() throws Exception {
        EntraClientCertificate cert = EntraClientCertificate.loadOrNull(CERT, KEY);
        assertNotNull(cert);
        assertValidAssertion(cert.assertion("my-client", "https://login.example/my-tenant/oauth2/v2.0/token"));
    }

    @Test
    void 憑證與私鑰同一檔時certificateKey可省略() throws Exception {
        EntraClientCertificate cert = EntraClientCertificate.loadOrNull(CERT_WITH_KEY, null);
        assertNotNull(cert);
        assertValidAssertion(cert.assertion("my-client", "https://login.example/my-tenant/oauth2/v2.0/token"));
    }

    @Test
    void 私鑰為PKCS1舊格式時提示轉換指令() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EntraClientCertificate.loadOrNull(CERT, KEY_PKCS1));
        assertTrue(e.getMessage().contains("PKCS#8"), e.getMessage());
        assertTrue(e.getMessage().contains("openssl pkcs8"), e.getMessage());
    }

    @Test
    void 憑證檔不存在時載入失敗並帶路徑() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EntraClientCertificate.loadOrNull("no/such/cert.pem", null));
        assertTrue(e.getMessage().contains("no/such/cert.pem"), e.getMessage());
    }

    @Test
    void 檔案內沒有憑證區塊時載入失敗() {
        // 只有私鑰、沒有 BEGIN CERTIFICATE 區塊
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EntraClientCertificate.loadOrNull(KEY, null));
        assertTrue(e.getMessage().contains("BEGIN CERTIFICATE"), e.getMessage());
    }

    /** 依微軟規格逐項驗證 assertion：標頭、宣告、效期，最後用憑證公鑰驗簽章 */
    private void assertValidAssertion(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));

        X509Certificate x509;
        try (FileInputStream in = new FileInputStream(CERT)) {
            x509 = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        String expectedThumb = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(x509.getEncoded()));

        assertEquals("RS256", header.path("alg").asText());
        assertEquals(expectedThumb, header.path("x5t#S256").asText());
        assertEquals("https://login.example/my-tenant/oauth2/v2.0/token", payload.path("aud").asText());
        assertEquals("my-client", payload.path("iss").asText());
        assertEquals("my-client", payload.path("sub").asText());
        assertFalse(payload.path("jti").asText().isEmpty());
        long now = Instant.now().getEpochSecond();
        assertTrue(payload.path("exp").asLong() > now, "assertion 應尚未過期");
        assertTrue(payload.path("nbf").asLong() <= now, "assertion 應已生效");

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(x509.getPublicKey());
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(sig.verify(Base64.getUrlDecoder().decode(parts[2])), "簽章應可用憑證公鑰驗證");
    }
}
