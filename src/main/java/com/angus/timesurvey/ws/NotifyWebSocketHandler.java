package com.angus.timesurvey.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** 依發起者識別碼（owner token）管理後台頁面的 WebSocket 連線，用來推播完成通知 */
@Component
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, Set<WebSocketSession>> sessionsByOwner = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String owner = ownerOf(session);
        if (owner == null) {
            try { session.close(CloseStatus.BAD_DATA); } catch (Exception ignored) {}
            return;
        }
        sessionsByOwner.computeIfAbsent(owner, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String owner = ownerOf(session);
        if (owner == null) return;
        Set<WebSocketSession> set = sessionsByOwner.get(owner);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) sessionsByOwner.remove(owner, set);
        }
    }

    /** 推播訊息給某發起者目前開啟的所有後台頁面 */
    public void notifyOwner(String owner, String json) {
        Set<WebSocketSession> set = sessionsByOwner.get(owner);
        if (set == null) return;
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : set) {
            try {
                if (s.isOpen()) s.sendMessage(msg);
            } catch (Exception ignored) {
            }
        }
    }

    /** 從連線網址 /ws/notify?owner=xxx 取出 owner token */
    private String ownerOf(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getQuery() == null) return null;
        for (String kv : session.getUri().getQuery().split("&")) {
            if (kv.startsWith("owner=") && kv.length() > 6) {
                return java.net.URLDecoder.decode(kv.substring(6), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
