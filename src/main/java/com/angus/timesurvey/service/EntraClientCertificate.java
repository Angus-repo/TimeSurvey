package com.angus.timesurvey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 以憑證簽章證明應用程式身分（client secret 的替代方案）：
 * 每次呼叫微軟 token 端點時，用憑證的 RSA 私鑰現簽一個短命 JWT（client assertion），
 * 微軟以 Azure 應用程式註冊中上傳的公鑰驗章；私鑰不出主機、線上只傳簽章。
 *
 * 憑證與私鑰皆為 PEM 格式，可放同一個檔案（entra.certificate），
 * 也可分開兩個檔案（私鑰另指定 entra.certificate-key）。
 * 私鑰需為 PKCS#8（{@code -----BEGIN PRIVATE KEY-----}）；
 * openssl 舊格式（{@code BEGIN RSA PRIVATE KEY}，PKCS#1）需先轉換，
 * 錯誤訊息會提示轉換指令。設定有誤時啟動即失敗，避免上線後才發現登入不了。
 */
public class EntraClientCertificate {

    private static final Pattern CERT_BLOCK =
            Pattern.compile("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);
    private static final Pattern KEY_BLOCK =
            Pattern.compile("-----BEGIN PRIVATE KEY-----(.*?)-----END PRIVATE KEY-----", Pattern.DOTALL);

    private final PrivateKey privateKey;
    /** JWT 標頭的 x5t#S256：憑證 DER 內容的 SHA-256 雜湊（base64url），微軟憑此挑選驗章公鑰 */
    private final String thumbprintS256;
    private final ObjectMapper mapper = new ObjectMapper();

    private EntraClientCertificate(PrivateKey privateKey, String thumbprintS256) {
        this.privateKey = privateKey;
        this.thumbprintS256 = thumbprintS256;
    }

    /** 依設定載入憑證；未設定（路徑空白）回 null，檔案有誤則直接拋出例外讓啟動失敗 */
    public static EntraClientCertificate loadOrNull(String certificatePath, String privateKeyPath) {
        if (certificatePath == null || certificatePath.isBlank()) {
            return null;
        }
        String certPem = readFile(certificatePath.trim());
        // 私鑰未另外指定時，視為與憑證放在同一個 PEM 檔
        String keyPem = (privateKeyPath == null || privateKeyPath.isBlank())
                ? certPem : readFile(privateKeyPath.trim());
        try {
            X509Certificate cert = parseCertificate(certPem, certificatePath);
            byte[] der = cert.getEncoded();
            String thumbprint = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(der));
            return new EntraClientCertificate(parsePrivateKey(keyPem, certificatePath, privateKeyPath), thumbprint);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解析 Entra 憑證失敗（" + certificatePath + "）：" + e.getMessage(), e);
        }
    }

    /** 簽發 client assertion（短命 JWT）：aud 為 token 端點網址，iss / sub 為 client id */
    public String assertion(String clientId, String audience) {
        try {
            ObjectNode header = mapper.createObjectNode();
            header.put("alg", "RS256");
            header.put("typ", "JWT");
            header.put("x5t#S256", thumbprintS256);

            long now = Instant.now().getEpochSecond();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("aud", audience);
            payload.put("iss", clientId);
            payload.put("sub", clientId);
            payload.put("jti", UUID.randomUUID().toString());
            payload.put("nbf", now - 60);        // 容忍與微軟間些許時鐘偏差
            payload.put("iat", now);
            payload.put("exp", now + 600);

            String signingInput = b64Url(mapper.writeValueAsBytes(header))
                    + "." + b64Url(mapper.writeValueAsBytes(payload));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + b64Url(sig.sign());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("簽發 client assertion 失敗：" + e.getMessage(), e);
        }
    }

    private static X509Certificate parseCertificate(String pem, String path) throws GeneralSecurityException {
        Matcher m = CERT_BLOCK.matcher(pem);
        if (!m.find()) {
            throw new IllegalStateException("Entra 憑證檔（" + path + "）找不到 BEGIN CERTIFICATE 區塊，"
                    + "請提供 PEM 格式的憑證");
        }
        byte[] der = Base64.getMimeDecoder().decode(m.group(1).strip());
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static PrivateKey parsePrivateKey(String pem, String certPath, String keyPath)
            throws GeneralSecurityException {
        String source = (keyPath == null || keyPath.isBlank()) ? certPath : keyPath;
        Matcher m = KEY_BLOCK.matcher(pem);
        if (!m.find()) {
            if (pem.contains("BEGIN RSA PRIVATE KEY")) {
                throw new IllegalStateException("Entra 憑證私鑰（" + source + "）是 PKCS#1 舊格式，"
                        + "請先轉成 PKCS#8：openssl pkcs8 -topk8 -nocrypt -in 舊私鑰.pem -out 新私鑰.pem");
            }
            throw new IllegalStateException("Entra 憑證私鑰（" + source + "）找不到 BEGIN PRIVATE KEY 區塊，"
                    + "請提供 PKCS#8 PEM 格式的私鑰（不加密）");
        }
        byte[] der = Base64.getMimeDecoder().decode(m.group(1).strip());
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("讀取 Entra 憑證檔失敗（" + path + "）：" + e.getMessage(), e);
        }
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
