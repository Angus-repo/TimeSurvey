package com.angus.timesurvey.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 每位登入者的 Microsoft Entra ID refresh token：首次登入（授權碼流程）時存入，
 * 之後由後端以 refresh token 換取 access token 呼叫 Microsoft Graph；
 * 微軟輪替 refresh token 時（回應帶新值）隨即更新。
 */
@Entity
@Table(name = "entra_token")
public class EntraToken {

    /** Entra ID 帳號的物件識別碼（id_token 的 oid 宣告，GUID） */
    @Id
    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(length = 255)
    private String displayName;

    /** 登入帳號（id_token 的 preferred_username，通常是 email） */
    @Column(length = 255)
    private String username;

    /** refresh token 長度可達數 KB，以 CLOB 存放 */
    @Lob
    @Column(nullable = false)
    private String refreshToken;

    private LocalDateTime updatedAt;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
