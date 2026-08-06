package com.bambi.service.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** 사용자에게 전달할 Service 소유 알림 Inbox 행. */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_key", nullable = false, length = 250)
    private String eventKey;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String body;

    @Column(name = "target_path", length = 500)
    private String targetPath;

    // 생성 유형(MORNING_BRIEFING|ON_DEMAND). 알림은 발행(claim) 시점에 만들어져 그때 값을
    // 그대로 저장한다 — 조회 시 리포트 재조인 없이 바로 내린다. 없으면 null(기존분·롤아웃 전).
    @Column(name = "report_type", length = 30)
    private String reportType;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Notification() {
    }

    /** 알림을 읽음으로 표시한다. 이미 읽은 경우 기존 시각을 유지한다. */
    public void markRead() {
        if (readAt == null) {
            readAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public String getReportType() {
        return reportType;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
