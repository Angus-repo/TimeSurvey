package com.angus.timesurvey.service;

import com.angus.timesurvey.model.EntraToken;
import com.angus.timesurvey.repo.EntraTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Microsoft Entra ID 授權碼流程與 Microsoft Graph 代呼叫（皆在後端進行）：
 *
 * <ol>
 *   <li>首次登入：導向微軟登入頁 → 回呼帶回授權碼 → 以 client secret 換取
 *       access token + refresh token，refresh token 存入資料庫（{@link EntraToken}）。</li>
 *   <li>之後每次呼叫 Graph：先用記憶體快取的 access token；過期時以資料庫的
 *       refresh token 換新的 access token（微軟輪替 refresh token 時同步回存）。</li>
 *   <li>refresh token 失效（撤銷、過期）→ 刪除該筆紀錄並拋出
 *       {@link NotSignedInException}，前端收到 401 會重新走登入流程。</li>
 * </ol>
 *
 * token / Graph 端點的基底網址可由建構參數覆寫（供測試以本機假伺服器驗證）。
 */
@Service
public class EntraGraphService {

    /** 未登入或 refresh token 已失效，需重新登入 */
    public static class NotSignedInException extends RuntimeException {
        public NotSignedInException(String msg) { super(msg); }
    }

    /** 呼叫微軟端點失敗（帶回可顯示給使用者的訊息） */
    public static class GraphException extends RuntimeException {
        public GraphException(String msg) { super(msg); }
    }

    /** 首次登入取得的使用者身分（來自 id_token）；會放進 HTTP session，需可序列化 */
    public record SignedInUser(String userId, String displayName, String username)
            implements java.io.Serializable {}

    /** 登入時請求的權限：offline_access 才拿得到 refresh token；
     *  MailboxSettings.Read 用於讀取信箱設定的時區（比較邀請者與被邀請者的時差） */
    static final String SCOPES = "openid profile email offline_access User.Read User.ReadBasic.All Calendars.Read MailboxSettings.Read";

    private static final Logger log = LoggerFactory.getLogger(EntraGraphService.class);

    private final String clientId;
    private final String clientSecret;
    private final String tenantId;
    private final String loginBase;
    private final String graphBase;
    private final EntraTokenRepository repo;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 各使用者 access token 的記憶體快取（提前 60 秒視為過期） */
    private record CachedToken(String accessToken, Instant expiresAt) {}
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public EntraGraphService(@Value("${entra.client-id:}") String clientId,
                             @Value("${entra.client-secret:}") String clientSecret,
                             @Value("${entra.tenant-id:common}") String tenantId,
                             @Value("${entra.login-base:https://login.microsoftonline.com}") String loginBase,
                             @Value("${entra.graph-base:https://graph.microsoft.com/v1.0}") String graphBase,
                             EntraTokenRepository repo) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? "common" : tenantId;
        this.loginBase = loginBase;
        this.graphBase = graphBase;
        this.repo = repo;
    }

    /** 是否啟用 Entra ID 登入：client-id 與 client-secret（後端流程必要）都有值 */
    public boolean enabled() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    /* ---------- 登入（授權碼流程） ---------- */

    /** 組出微軟登入頁（authorize 端點）的網址 */
    public String authorizeUrl(String redirectUri, String state) {
        return loginBase + "/" + tenantId + "/oauth2/v2.0/authorize"
                + "?client_id=" + enc(clientId)
                + "&response_type=code&response_mode=query"
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(SCOPES)
                + "&state=" + enc(state);
    }

    /** 微軟的登出網址（front-channel logout，登出後導回本站） */
    public String logoutUrl(String postLogoutRedirectUri) {
        return loginBase + "/" + tenantId + "/oauth2/v2.0/logout"
                + "?post_logout_redirect_uri=" + enc(postLogoutRedirectUri);
    }

    /** 以授權碼換取 token，refresh token 存入資料庫，回傳登入者身分 */
    public SignedInUser redeemCode(String code, String redirectUri) {
        JsonNode res = tokenRequest(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri));
        JsonNode claims = parseJwtPayload(res.path("id_token").asText());
        String userId = claims.path("oid").asText("");
        if (userId.isEmpty()) {
            // 個人 Microsoft 帳戶等少數情況沒有 oid，退而用 sub（同樣能唯一識別）
            userId = claims.path("sub").asText("");
        }
        if (userId.isEmpty()) {
            throw new GraphException("登入回應缺少使用者識別碼（id_token 無 oid/sub）");
        }
        SignedInUser user = new SignedInUser(userId,
                claims.path("name").asText(null),
                claims.path("preferred_username").asText(null));

        String refreshToken = res.path("refresh_token").asText("");
        if (refreshToken.isEmpty()) {
            throw new GraphException("登入回應未包含 refresh token（請確認已授權 offline_access 權限）");
        }
        EntraToken row = repo.findById(userId).orElseGet(EntraToken::new);
        row.setUserId(userId);
        row.setDisplayName(user.displayName());
        row.setUsername(user.username());
        row.setRefreshToken(refreshToken);
        row.setUpdatedAt(LocalDateTime.now());
        repo.save(row);
        cacheAccessToken(userId, res);
        return user;
    }

    /** 登出：刪除資料庫中的 refresh token（含記住我權杖雜湊）與快取的 access token */
    public void signOut(String userId) {
        tokenCache.remove(userId);
        repo.deleteById(userId);
    }

    /* ---------- 記住我（remember-me）權杖 ---------- */

    private final SecureRandom random = new SecureRandom();

    /** 登入成功後發放記住我權杖：原始值放進瀏覽器 cookie，資料庫僅存 SHA-256 雜湊，
     *  資料庫外洩也無法憑雜湊冒用登入。每次登入重新產生，舊 cookie 隨之失效 */
    public String issueRememberToken(String userId) {
        EntraToken row = repo.findById(userId).orElse(null);
        if (row == null) {
            return null;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        row.setRememberTokenHash(sha256(token));
        row.setUpdatedAt(LocalDateTime.now());
        repo.save(row);
        return token;
    }

    /** 以記住我權杖還原登入者；查無對應（已登出、權杖已更換）回 null */
    public SignedInUser userByRememberToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return repo.findByRememberTokenHash(sha256(token))
                .map(r -> new SignedInUser(r.getUserId(), r.getDisplayName(), r.getUsername()))
                .orElse(null);
    }

    static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支援 SHA-256", e);
        }
    }

    /* ---------- access token：快取 → 過期以 refresh token 換新 ---------- */

    /** 取得該使用者的 access token；無 refresh token 或已失效時拋出 NotSignedInException */
    String accessToken(String userId) {
        CachedToken cached = tokenCache.get(userId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }
        EntraToken row = repo.findById(userId)
                .orElseThrow(() -> new NotSignedInException("尚未登入或登入已失效，請重新登入"));
        JsonNode res;
        try {
            res = tokenRequest(Map.of(
                    "grant_type", "refresh_token",
                    "refresh_token", row.getRefreshToken()));
        } catch (GraphException e) {
            // refresh token 已被撤銷或過期：移除紀錄，讓前端重新走登入流程
            log.info("[Entra] 使用者 {} 的 refresh token 已失效，需重新登入：{}", userId, e.getMessage());
            repo.deleteById(userId);
            tokenCache.remove(userId);
            throw new NotSignedInException("登入已過期，請重新登入");
        }
        // 微軟會不定期輪替 refresh token，回應帶了新值就回存資料庫
        String rotated = res.path("refresh_token").asText("");
        if (!rotated.isEmpty() && !rotated.equals(row.getRefreshToken())) {
            row.setRefreshToken(rotated);
            row.setUpdatedAt(LocalDateTime.now());
            repo.save(row);
        }
        return cacheAccessToken(userId, res);
    }

    private String cacheAccessToken(String userId, JsonNode tokenResponse) {
        String accessToken = tokenResponse.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new GraphException("微軟回應未包含 access token");
        }
        long expiresIn = tokenResponse.path("expires_in").asLong(300);
        tokenCache.put(userId, new CachedToken(accessToken, Instant.now().plusSeconds(Math.max(60, expiresIn) - 60)));
        return accessToken;
    }

    /** 呼叫 token 端點（共用參數 client_id / client_secret / scope 在此補上） */
    private JsonNode tokenRequest(Map<String, String> params) {
        Map<String, String> form = new LinkedHashMap<>(params);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("scope", SCOPES);
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=').append(enc(v)));

        HttpRequest req = HttpRequest.newBuilder(URI.create(loginBase + "/" + tenantId + "/oauth2/v2.0/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = send(req);
        JsonNode json = readJson(res.body());
        if (res.statusCode() != 200) {
            String err = json.path("error_description").asText(json.path("error").asText("HTTP " + res.statusCode()));
            throw new GraphException("向微軟換取 token 失敗：" + err);
        }
        return json;
    }

    /* ---------- Microsoft Graph 代呼叫 ---------- */

    private static final String USER_SELECT = "$select=id,displayName,department,jobTitle,mail,userPrincipalName";

    /** 以顯示名稱「精確比對」查詢組織中的使用者（含部門、email） */
    public List<Map<String, Object>> searchUsers(String userId, String name) {
        String filter = "displayName eq '" + name.replace("'", "''") + "'";
        JsonNode d = graphGet(userId, "/users?$filter=" + enc(filter) + "&" + USER_SELECT, null);
        return usersFrom(d);
    }

    /** $search 是否可用（進階查詢部分租戶未支援）；null=尚未測過，失敗一次後改走 startswith */
    private volatile Boolean searchSupported = null;

    /** 輸入時的人名建議（最多 8 筆）：優先用 $search 比對顯示名稱各斷詞的字首
     *（例如輸入「an」可找到「Angus Luo」）；不支援時退回 startswith（僅比對整串開頭） */
    public List<Map<String, Object>> suggestUsers(String userId, String prefix) {
        String suffix = "&" + USER_SELECT + "&$top=8";
        if (!Boolean.FALSE.equals(searchSupported)) {
            String q = "\"displayName:" + prefix.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            try {
                JsonNode d = graphGet(userId, "/users?$search=" + enc(q) + "&$count=true" + suffix,
                        Map.of("ConsistencyLevel", "eventual"));
                searchSupported = true;
                return usersFrom(d);
            } catch (GraphException e) {
                if (Boolean.TRUE.equals(searchSupported)) {
                    throw e;   // 已確認支援過卻失敗 → 是真的錯誤，不該退回 startswith 掩蓋
                }
                log.info("[Entra] $search 進階查詢不可用，人名建議改用 startswith：{}", e.getMessage());
                searchSupported = false;
            }
        }
        String filter = "startswith(displayName,'" + prefix.replace("'", "''") + "')";
        JsonNode d = graphGet(userId, "/users?$filter=" + enc(filter) + suffix, null);
        return usersFrom(d);
    }

    private List<Map<String, Object>> usersFrom(JsonNode d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode u : d.path("value")) {
            out.add(mapper.convertValue(u, LinkedHashMap.class));
        }
        return out;
    }

    /** 讀取登入者行事曆（時間為台北時區），自動翻頁取完所有事件 */
    public List<Map<String, Object>> calendarView(String userId, String startIso, String endIso) {
        String path = "/me/calendarView?startDateTime=" + enc(startIso) + "&endDateTime=" + enc(endIso)
                + "&$select=subject,organizer,start,end,isAllDay&$orderby=start/dateTime&$top=100";
        List<Map<String, Object>> out = new ArrayList<>();
        while (path != null) {
            JsonNode d = graphGet(userId, path, Map.of("Prefer", "outlook.timezone=\"Taipei Standard Time\""));
            for (JsonNode ev : d.path("value")) {
                out.add(mapper.convertValue(ev, LinkedHashMap.class));
            }
            String next = d.path("@odata.nextLink").asText("");
            path = next.isEmpty() ? null : next.replace(graphBase, "");
        }
        return out;
    }

    /** 各使用者信箱時區的快取（key＝Graph 使用者 id；空字串＝查過但取不到，避免重複呼叫 Graph） */
    private final Map<String, String> mailboxTzCache = new ConcurrentHashMap<>();

    /** 查詢組織中某使用者信箱設定的時區，回傳 {@code {timeZone, iana}}（iana 查無對應時省略）。
     *  Graph 回傳的多為 Windows 時區名稱（例如 "Taipei Standard Time"），一併轉成 IANA
     *  識別碼供前端計算時差。委派權限的租戶原則可能僅允許讀取登入者自己的信箱設定，
     *  讀不到（權限不足、對方無 Exchange 信箱等）回傳 null、不視為錯誤 */
    public Map<String, String> userTimeZone(String userId, String targetUserId) {
        String raw = mailboxTzCache.get(targetUserId);
        if (raw == null) {
            try {
                JsonNode d = graphGet(userId, "/users/" + enc(targetUserId) + "/mailboxSettings/timeZone", null);
                raw = d.path("value").asText("");
            } catch (GraphException e) {
                log.debug("[Entra] 讀取使用者 {} 的信箱時區失敗：{}", targetUserId, e.getMessage());
                raw = "";
            }
            mailboxTzCache.put(targetUserId, raw);
        }
        if (raw.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("timeZone", raw);
        String iana = WindowsTimeZones.toIana(raw);
        if (iana != null) {
            out.put("iana", iana);
        }
        return out;
    }

    /** 登入者的個人資料（帳號資訊卡用） */
    public Map<String, Object> profile(String userId) {
        JsonNode d = graphGet(userId,
                "/me?$select=displayName,mail,userPrincipalName,jobTitle,department,officeLocation,mobilePhone,businessPhones",
                null);
        return mapper.convertValue(d, LinkedHashMap.class);
    }

    /** 登入者的大頭照（96x96）；沒有照片時回傳 null */
    public byte[] photo(String userId) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(graphBase + "/me/photos/96x96/$value"))
                .header("Authorization", "Bearer " + accessToken(userId))
                .GET().build();
        HttpResponse<byte[]> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new GraphException("連線 Microsoft Graph 失敗：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException("連線 Microsoft Graph 遭中斷");
        }
        return res.statusCode() == 200 ? res.body() : null;
    }

    /** GET Graph API；access token 剛好失效（401）時強制換新重試一次 */
    private JsonNode graphGet(String userId, String pathWithQuery, Map<String, String> extraHeaders) {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(graphBase + pathWithQuery))
                    .header("Authorization", "Bearer " + accessToken(userId))
                    .GET();
            if (extraHeaders != null) {
                extraHeaders.forEach(b::header);
            }
            HttpResponse<String> res = send(b.build());
            if (res.statusCode() == 401 && attempt == 0) {
                tokenCache.remove(userId);
                continue;
            }
            JsonNode json = readJson(res.body());
            if (res.statusCode() >= 400) {
                String message = json.path("error").path("message").asText("");
                if (message.isEmpty()) {
                    // Outlook 系端點（行事曆、信箱）對沒有 Exchange Online 信箱的帳號
                    // （如未指派授權或外部帳號）會回空 body 的 401，與權限設定無關
                    message = res.statusCode() == 401
                            ? "此帳號可能沒有 Exchange Online 信箱（行事曆），請改用具備 Microsoft 365 授權的帳號登入"
                            : "(無錯誤訊息)";
                }
                throw new GraphException("Graph API 呼叫失敗（" + res.statusCode() + "）：" + message);
            }
            return json;
        }
    }

    /* ---------- 工具 ---------- */

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GraphException("連線微軟服務失敗：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException("連線微軟服務遭中斷");
        }
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw new GraphException("微軟服務回應不是有效的 JSON");
        }
    }

    /** 解出 JWT 的 payload（id_token 直接來自微軟 token 端點的 TLS 回應，毋須再驗簽章） */
    static JsonNode parseJwtPayload(String jwt) {
        String[] parts = String.valueOf(jwt).split("\\.");
        if (parts.length < 2) {
            throw new GraphException("id_token 格式不正確");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        try {
            return new ObjectMapper().readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GraphException("id_token 內容無法解析");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
