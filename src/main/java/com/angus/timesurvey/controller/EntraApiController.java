package com.angus.timesurvey.controller;

import com.angus.timesurvey.service.EntraGraphService;
import com.angus.timesurvey.service.EntraGraphService.GraphException;
import com.angus.timesurvey.service.EntraGraphService.NotSignedInException;
import com.angus.timesurvey.service.EntraGraphService.SignedInUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Microsoft Entra ID 登入與 Microsoft Graph 代呼叫 API。
 *
 * 登入採後端授權碼流程：{@code /api/entra/login} 導向微軟登入頁 →
 * {@code /api/entra/callback} 以 client secret 換取 token、refresh token 存入資料庫、
 * 登入者記在 HTTP session → 之後前端呼叫 {@code /users}、{@code /calendar} 等代理端點，
 * 由 {@link EntraGraphService} 用 refresh token 換 access token 呼叫 Graph。
 * 未登入（或 refresh token 失效）一律回 401，前端據此重新走登入流程。
 *
 * 設定由 {@code data/entra.properties} 注入（{@link com.angus.timesurvey.config.EntraEnvironmentPostProcessor}）；
 * 未設定 CLIENT_ID／CLIENT_SECRET 時 {@code /api/entra-config} 回 204，前端不啟用登入。
 */
@RestController
public class EntraApiController {

    /** session 屬性：登入者的 Entra ID 物件識別碼 */
    static final String SESSION_USER = "entraUserId";
    /** session 屬性：授權碼流程的 state 防偽值與登入後要回去的頁面 */
    static final String SESSION_STATE = "entraState";
    static final String SESSION_RETURN = "entraReturn";

    private final EntraGraphService graph;

    public EntraApiController(EntraGraphService graph) {
        this.graph = graph;
    }

    /** 前端據此決定是否啟用 Entra ID 登入：未設定回 204 */
    @GetMapping("/api/entra-config")
    public ResponseEntity<Map<String, String>> config() {
        if (!graph.enabled()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("loginPath", "/api/entra/login"));
    }

    /** 目前登入者；未登入回 401（前端顯示登入遮罩） */
    @GetMapping("/api/entra/me")
    public Map<String, Object> me(HttpServletRequest request) {
        SignedInUser user = requireUser(request);
        return Map.of("displayName", nullToEmpty(user.displayName()),
                      "username", nullToEmpty(user.username()));
    }

    /** 導向微軟登入頁；return 為登入完成後要回去的站內路徑 */
    @GetMapping("/api/entra/login")
    public ResponseEntity<Void> login(@RequestParam(name = "return", defaultValue = "/") String returnPath,
                                      HttpServletRequest request) {
        String state = UUID.randomUUID().toString();
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_STATE, state);
        session.setAttribute(SESSION_RETURN, safeReturnPath(returnPath));
        return redirect(graph.authorizeUrl(callbackUri(request), state));
    }

    /** 微軟登入完成的回呼：驗 state、換 token、存 refresh token、寫入 session 後導回原頁 */
    @GetMapping("/api/entra/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false) String errorDescription,
                                         HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String returnPath = safeReturnPath((String) session.getAttribute(SESSION_RETURN));
        String expectedState = (String) session.getAttribute(SESSION_STATE);
        session.removeAttribute(SESSION_STATE);
        session.removeAttribute(SESSION_RETURN);

        if (error != null) {
            return redirect(returnPath + "#entra_error=" + enc(errorDescription != null ? errorDescription : error));
        }
        if (code == null || expectedState == null || !expectedState.equals(state)) {
            return redirect(returnPath + "#entra_error=" + enc("登入回應驗證失敗（state 不符），請重新登入"));
        }
        try {
            SignedInUser user = graph.redeemCode(code, callbackUri(request));
            session.setAttribute(SESSION_USER, user);
        } catch (GraphException e) {
            return redirect(returnPath + "#entra_error=" + enc(e.getMessage()));
        }
        return redirect(returnPath);
    }

    /** 登出：清除 session 與資料庫中的 refresh token，並導向微軟登出頁 */
    @GetMapping("/api/entra/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            SignedInUser user = (SignedInUser) session.getAttribute(SESSION_USER);
            if (user != null) {
                graph.signOut(user.userId());
            }
            session.invalidate();
        }
        String origin = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        return redirect(graph.logoutUrl(origin));
    }

    /* ---------- Microsoft Graph 代理 ---------- */

    /** 以顯示名稱精確查詢組織中的使用者 */
    @GetMapping("/api/entra/users")
    public List<Map<String, Object>> users(@RequestParam String name, HttpServletRequest request) {
        return graph.searchUsers(requireUser(request).userId(), name);
    }

    /** 輸入時的人名建議；未滿 2 個字不查詢，直接回空陣列 */
    @GetMapping("/api/entra/suggest")
    public List<Map<String, Object>> suggest(@RequestParam String name, HttpServletRequest request) {
        SignedInUser user = requireUser(request);
        String prefix = name.trim();
        if (prefix.length() < 2) {
            return List.of();
        }
        return graph.suggestUsers(user.userId(), prefix);
    }

    /** 登入者行事曆（start / end 形如 2026-07-06T00:00:00，回傳台北時區的事件） */
    @GetMapping("/api/entra/calendar")
    public List<Map<String, Object>> calendar(@RequestParam String start, @RequestParam String end,
                                              HttpServletRequest request) {
        return graph.calendarView(requireUser(request).userId(), start, end);
    }

    /** 登入者個人資料（帳號資訊卡） */
    @GetMapping("/api/entra/profile")
    public Map<String, Object> profile(HttpServletRequest request) {
        return graph.profile(requireUser(request).userId());
    }

    /** 登入者大頭照；沒有照片回 404 */
    @GetMapping("/api/entra/photo")
    public ResponseEntity<byte[]> photo(HttpServletRequest request) {
        byte[] bytes = graph.photo(requireUser(request).userId());
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
    }

    /* ---------- 例外 → HTTP 狀態 ---------- */

    @ExceptionHandler(NotSignedInException.class)
    public ResponseEntity<Map<String, String>> notSignedIn(NotSignedInException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(GraphException.class)
    public ResponseEntity<Map<String, String>> graphError(GraphException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
    }

    /* ---------- 工具 ---------- */

    private SignedInUser requireUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        SignedInUser user = session == null ? null : (SignedInUser) session.getAttribute(SESSION_USER);
        if (user == null) {
            throw new NotSignedInException("尚未登入，請先以 Microsoft 帳號登入");
        }
        return user;
    }

    /** 回呼網址固定為本站的 /api/entra/callback（需以「Web」平台註冊於 Azure 應用程式） */
    private String callbackUri(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request).path("/api/entra/callback").build().toUriString();
    }

    /** 登入後導回的路徑僅允許站內相對路徑，避免 open redirect */
    static String safeReturnPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            return "/";
        }
        return path;
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
