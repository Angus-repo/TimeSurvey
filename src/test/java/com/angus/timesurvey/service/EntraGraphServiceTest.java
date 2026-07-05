package com.angus.timesurvey.service;

import com.angus.timesurvey.model.EntraToken;
import com.angus.timesurvey.repo.EntraTokenRepository;
import com.angus.timesurvey.service.EntraGraphService.NotSignedInException;
import com.angus.timesurvey.service.EntraGraphService.SignedInUser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 以本機假伺服器扮演微軟 token／Graph 端點，驗證授權碼流程與 refresh token 機制：
 * 首次登入把 refresh token 存進資料庫、之後以 refresh token 換 access token（輪替時回存）、
 * refresh token 失效時刪除紀錄並要求重新登入。
 */
class EntraGraphServiceTest {

    private HttpServer server;
    private String base;
    private final List<String> tokenRequests = new ArrayList<>();   // token 端點收到的表單內容
    private volatile int tokenStatus = 200;
    private volatile String tokenResponse = "{}";
    private volatile String graphResponse = "{}";
    private volatile int graphStatus = 200;                              // 200 以外時回空 body（模擬 Outlook 端點）
    private final List<String> graphPaths = new ArrayList<>();          // Graph 端點收到的路徑
    private final List<String> graphConsistency = new ArrayList<>();    // 各請求的 ConsistencyLevel 標頭
    private volatile boolean searchUnsupported = false;                 // 模擬 $search 不支援

    private final EntraTokenRepository repo = mock(EntraTokenRepository.class);
    private EntraGraphService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/my-tenant/oauth2/v2.0/token", ex -> {
            tokenRequests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = tokenResponse.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(tokenStatus, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/graph/", ex -> {
            String uri = ex.getRequestURI().toString();
            graphPaths.add(uri);
            graphConsistency.add(ex.getRequestHeaders().getFirst("ConsistencyLevel"));
            byte[] body;
            int status;
            if (graphStatus != 200) {
                // 模擬 Outlook 系端點對沒有信箱的帳號回空 body 的錯誤
                ex.sendResponseHeaders(graphStatus, -1);
                ex.close();
                return;
            }
            if (searchUnsupported && uri.contains("$search=")) {
                // 模擬租戶不支援進階查詢
                status = 400;
                body = "{\"error\":{\"code\":\"Request_UnsupportedQuery\",\"message\":\"unsupported\"}}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = graphResponse.getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new EntraGraphService("my-client", "my-secret", "my-tenant",
                base, base + "/graph", repo);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 組出只含 payload 的假 id_token（服務端不驗簽章，直接解 payload） */
    private static String fakeIdToken(String payloadJson) {
        String enc = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "x." + enc + ".y";
    }

    @Test
    void 未設定CLIENT_SECRET時視為未啟用() {
        EntraGraphService none = new EntraGraphService("my-client", "", "my-tenant", base, base, repo);
        assertFalse(none.enabled());
        assertTrue(service.enabled());
    }

    @Test
    void 首次登入以授權碼換token並把refreshToken存入資料庫() {
        when(repo.findById("oid-1")).thenReturn(Optional.empty());
        tokenResponse = "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600," +
                "\"id_token\":\"" + fakeIdToken(
                "{\"oid\":\"oid-1\",\"name\":\"王小明\",\"preferred_username\":\"ming@example.com\"}") + "\"}";

        SignedInUser user = service.redeemCode("auth-code", "http://localhost/api/entra/callback");

        assertEquals(new SignedInUser("oid-1", "王小明", "ming@example.com"), user);
        assertTrue(tokenRequests.get(0).contains("grant_type=authorization_code"));
        assertTrue(tokenRequests.get(0).contains("client_secret=my-secret"));
        ArgumentCaptor<EntraToken> saved = ArgumentCaptor.forClass(EntraToken.class);
        verify(repo).save(saved.capture());
        assertEquals("rt-1", saved.getValue().getRefreshToken());
        assertEquals("oid-1", saved.getValue().getUserId());
    }

    private EntraToken row(String userId, String refreshToken) {
        EntraToken t = new EntraToken();
        t.setUserId(userId);
        t.setRefreshToken(refreshToken);
        return t;
    }

    @Test
    void 呼叫Graph時以資料庫的refreshToken換取accessToken() {
        when(repo.findById("oid-1")).thenReturn(Optional.of(row("oid-1", "rt-1")));
        tokenResponse = "{\"access_token\":\"at-2\",\"expires_in\":3600}";
        graphResponse = "{\"value\":[{\"displayName\":\"王小明\",\"mail\":\"ming@example.com\"}]}";

        var users = service.searchUsers("oid-1", "王小明");

        assertEquals(1, users.size());
        assertEquals("王小明", users.get(0).get("displayName"));
        assertTrue(tokenRequests.get(0).contains("grant_type=refresh_token"));
        assertTrue(tokenRequests.get(0).contains("refresh_token=rt-1"));
    }

    @Test
    void accessToken快取未過期時不重複呼叫token端點() {
        when(repo.findById("oid-1")).thenReturn(Optional.of(row("oid-1", "rt-1")));
        tokenResponse = "{\"access_token\":\"at-2\",\"expires_in\":3600}";
        graphResponse = "{\"value\":[]}";

        service.searchUsers("oid-1", "王小明");
        service.searchUsers("oid-1", "李大華");

        assertEquals(1, tokenRequests.size(), "第二次呼叫應直接用快取的 access token");
    }

    @Test
    void 微軟輪替refreshToken時回存資料庫() {
        EntraToken existing = row("oid-1", "rt-old");
        when(repo.findById("oid-1")).thenReturn(Optional.of(existing));
        tokenResponse = "{\"access_token\":\"at-3\",\"refresh_token\":\"rt-new\",\"expires_in\":3600}";
        graphResponse = "{\"value\":[]}";

        service.searchUsers("oid-1", "王小明");

        verify(repo).save(any(EntraToken.class));
        assertEquals("rt-new", existing.getRefreshToken());
    }

    @Test
    void refreshToken失效時刪除紀錄並要求重新登入() {
        when(repo.findById("oid-1")).thenReturn(Optional.of(row("oid-1", "rt-dead")));
        tokenStatus = 400;
        tokenResponse = "{\"error\":\"invalid_grant\",\"error_description\":\"token expired\"}";

        assertThrows(NotSignedInException.class, () -> service.searchUsers("oid-1", "王小明"));
        verify(repo).deleteById("oid-1");
    }

    @Test
    void 人名建議以search查詢並帶ConsistencyLevel標頭() {
        when(repo.findById("oid-1")).thenReturn(Optional.of(row("oid-1", "rt-1")));
        tokenResponse = "{\"access_token\":\"at-1\",\"expires_in\":3600}";
        graphResponse = "{\"value\":[{\"displayName\":\"Angus Luo\"}]}";

        var users = service.suggestUsers("oid-1", "an");

        assertEquals(1, users.size());
        assertEquals("Angus Luo", users.get(0).get("displayName"));
        assertTrue(graphPaths.get(0).contains("$search="), "應使用 $search 查詢：" + graphPaths.get(0));
        assertEquals("eventual", graphConsistency.get(0));
    }

    @Test
    void search不支援時退回startswith且之後不再嘗試() {
        when(repo.findById("oid-1")).thenReturn(Optional.of(row("oid-1", "rt-1")));
        tokenResponse = "{\"access_token\":\"at-1\",\"expires_in\":3600}";
        graphResponse = "{\"value\":[{\"displayName\":\"Angus Luo\"}]}";
        searchUnsupported = true;

        var users = service.suggestUsers("oid-1", "an");
        assertEquals(1, users.size());
        assertTrue(graphPaths.get(0).contains("$search="), "第一次應先嘗試 $search");
        assertTrue(graphPaths.get(1).contains("startswith"), "失敗後退回 startswith");

        service.suggestUsers("oid-1", "lu");
        assertEquals(3, graphPaths.size(), "之後不再嘗試 $search");
        assertTrue(graphPaths.get(2).contains("startswith"));
    }

    @Test
    void Graph回空body的401時提示帳號可能沒有信箱() {
        when(repo.findById("oid-1")).thenReturn(Optional.of(row("oid-1", "rt-1")));
        tokenResponse = "{\"access_token\":\"at-1\",\"expires_in\":3600}";
        graphStatus = 401;

        var e = assertThrows(EntraGraphService.GraphException.class,
                () -> service.calendarView("oid-1", "2026-07-05T00:00:00", "2026-07-11T23:59:59"));

        assertTrue(e.getMessage().contains("Exchange Online"), "應提示可能沒有信箱：" + e.getMessage());
        assertEquals(2, graphPaths.size(), "401 應先換新 token 重試一次");
    }

    @Test
    void 資料庫沒有refreshToken時視為未登入() {
        when(repo.findById("oid-x")).thenReturn(Optional.empty());
        assertThrows(NotSignedInException.class, () -> service.searchUsers("oid-x", "王小明"));
    }
}
