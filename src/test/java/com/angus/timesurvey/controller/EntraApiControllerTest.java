package com.angus.timesurvey.controller;

import com.angus.timesurvey.model.UserActivity;
import com.angus.timesurvey.repo.UserActivityRepository;
import com.angus.timesurvey.service.EntraGraphService;
import com.angus.timesurvey.service.EntraGraphService.NotSignedInException;
import com.angus.timesurvey.service.EntraGraphService.SignedInUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 驗證 Entra ID API：未設定時設定 API 回 204；登入導向微軟並帶 state；
 * 回呼驗 state；未登入呼叫代理 API 拋出未登入例外（對應 401）。
 */
class EntraApiControllerTest {

    private final EntraGraphService graph = mock(EntraGraphService.class);
    private final UserActivityRepository activityRepo = mock(UserActivityRepository.class);
    private final EntraApiController controller = new EntraApiController(graph, activityRepo);

    @Test
    void 未設定時設定API回傳204() {
        when(graph.enabled()).thenReturn(false);
        assertEquals(HttpStatus.NO_CONTENT, controller.config().getStatusCode());
    }

    @Test
    void 已設定時設定API回傳登入路徑() {
        when(graph.enabled()).thenReturn(true);
        var res = controller.config();
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("/api/entra/login", res.getBody().get("loginPath"));
    }

    @Test
    void 登入時導向微軟登入頁並於session記下state() {
        when(graph.authorizeUrl(anyString(), anyString())).thenReturn("https://login.example/authorize");
        MockHttpServletRequest req = new MockHttpServletRequest();
        var res = controller.login("/stats", req);
        assertEquals(HttpStatus.FOUND, res.getStatusCode());
        assertEquals("https://login.example/authorize", res.getHeaders().getLocation().toString());
        assertNotNull(req.getSession().getAttribute(EntraApiController.SESSION_STATE));
        assertEquals("/stats", req.getSession().getAttribute(EntraApiController.SESSION_RETURN));
    }

    @Test
    void 回呼state不符時不換token並帶錯誤導回() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(EntraApiController.SESSION_STATE, "expected");
        req.getSession().setAttribute(EntraApiController.SESSION_RETURN, "/");
        var res = controller.callback("some-code", "wrong", null, null, req);
        assertEquals(HttpStatus.FOUND, res.getStatusCode());
        assertTrue(res.getHeaders().getLocation().toString().contains("#entra_error="));
        verify(graph, never()).redeemCode(anyString(), anyString());
    }

    @Test
    void 回呼成功時登入者寫入session並導回原頁() {
        SignedInUser user = new SignedInUser("u1", "王小明", "ming@example.com");
        when(graph.redeemCode(anyString(), anyString())).thenReturn(user);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(EntraApiController.SESSION_STATE, "st");
        req.getSession().setAttribute(EntraApiController.SESSION_RETURN, "/s/abc");
        var res = controller.callback("code-1", "st", null, null, req);
        assertEquals("/s/abc", res.getHeaders().getLocation().toString());
        assertEquals(user, req.getSession().getAttribute(EntraApiController.SESSION_USER));
    }

    @Test
    void 未登入呼叫代理API拋出未登入例外() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThrows(NotSignedInException.class, () -> controller.users("王小明", req));
        assertThrows(NotSignedInException.class,
                () -> controller.calendar("2026-07-06T00:00:00", "2026-07-07T00:00:00", req));
        assertThrows(NotSignedInException.class, () -> controller.me("/", req));
        assertThrows(NotSignedInException.class, () -> controller.suggest("an", req));
    }

    @Test
    void 人名建議未滿2個字時回空陣列不查詢Graph() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(EntraApiController.SESSION_USER,
                new SignedInUser("u1", "王小明", "ming@example.com"));
        assertEquals(0, controller.suggest("a ", req).size());
        verify(graph, never()).suggestUsers(anyString(), anyString());
    }

    @Test
    void 已登入時me回傳登入者資訊() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(EntraApiController.SESSION_USER,
                new SignedInUser("u1", "王小明", "ming@example.com"));
        Map<String, Object> me = controller.me("/stats", req);
        assertEquals("王小明", me.get("displayName"));
        assertEquals("ming@example.com", me.get("username"));
    }

    @Test
    void me時記錄一筆使用紀錄含姓名email與頁面() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(EntraApiController.SESSION_USER,
                new SignedInUser("u1", "王小明", "ming@example.com"));
        controller.me("/s/abc", req);
        ArgumentCaptor<UserActivity> cap = ArgumentCaptor.forClass(UserActivity.class);
        verify(activityRepo).save(cap.capture());
        UserActivity a = cap.getValue();
        assertEquals("u1", a.getUserId());
        assertEquals("王小明", a.getUserName());
        assertEquals("ming@example.com", a.getUserEmail());
        assertEquals("/s/abc", a.getPage());
        assertNotNull(a.getOccurredAt());
    }

    @Test
    void 統計頁不列入使用量紀錄() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(EntraApiController.SESSION_USER,
                new SignedInUser("u1", "王小明", "ming@example.com"));
        Map<String, Object> me = controller.me("/stats", req);
        assertEquals("王小明", me.get("displayName"));   // me 仍正常回應（頁首頭像用）
        verify(activityRepo, never()).save(any());       // 但不寫入使用紀錄，避免查看統計就灌水
    }

    @Test
    void 使用紀錄寫入失敗不影響me回應() {
        when(activityRepo.save(any())).thenThrow(new RuntimeException("db down"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute(EntraApiController.SESSION_USER,
                new SignedInUser("u1", "王小明", "ming@example.com"));
        Map<String, Object> me = controller.me("/", req);
        assertEquals("王小明", me.get("displayName"));
    }

    @Test
    void 導回路徑僅允許站內相對路徑() {
        assertEquals("/stats", EntraApiController.safeReturnPath("/stats"));
        assertEquals("/", EntraApiController.safeReturnPath(null));
        assertEquals("/", EntraApiController.safeReturnPath("https://evil.example"));
        assertEquals("/", EntraApiController.safeReturnPath("//evil.example"));
    }
}
