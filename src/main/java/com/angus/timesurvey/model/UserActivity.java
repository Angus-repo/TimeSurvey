package com.angus.timesurvey.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Entra ID 登入者的一次頁面使用紀錄（姓名、email、頁面、時間）。
 * 僅在啟用 Entra ID 登入時寫入；不隨調查刪除，供統計頁呈現
 * 近兩週～半年的整體／個人使用量趨勢。保留月數由 stats.retention-months
 * 設定（預設 6 個月），每月 1 號的 Housekeeping 批次清除過舊紀錄。
 */
@Entity
@Table(name = "user_activity", indexes = @Index(name = "idx_user_activity_at", columnList = "occurredAt"))
public class UserActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登入者的 Entra ID 物件識別碼 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 登入者顯示名稱（id_token 的 name） */
    @Column(name = "user_name")
    private String userName;

    /** 登入者 email（id_token 的 preferred_username，通常即 UPN） */
    @Column(name = "user_email")
    private String userEmail;

    /** 開啟的頁面路徑（如 /、/s/{id}、/stats） */
    @Column(length = 128)
    private String page;

    private LocalDateTime occurredAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
