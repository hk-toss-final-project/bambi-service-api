package com.bambi.service.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 생성 접수(펜딩) 기록 (service.generation_pendings) — 프론트 "처리중" 슬롯의 근거.
 * id 는 service 가 멱등키에서 파생한 결정적 UUID(같은 접수 = 같은 행, 연타 중복 방지).
 * 저장은 {@link GenerationPendingRepository#insertPending} 멱등 insert 로만 한다 —
 * 재접수(스케줄러 재시도·같은 분 연타)는 ON CONFLICT DO NOTHING 으로 흡수된다.
 *
 * <p>status 는 PENDING/RUNNING/PUBLISHING/COMPLETED/FAILED/CANCELLED 수명주기를 사용한다.
 * Agent Job 완료 후에도 Publish Snapshot이 Service DB에 반영될 때까지 PUBLISHING을 유지한다.
 */
@Entity
@Table(name = "generation_pendings")
public class GenerationPending {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    // 생성 유형(MORNING_BRIEFING|ON_DEMAND) — 접수 시점에 service 가 직접 아는 값.
    @Column(name = "report_type", length = 30)
    private String reportType;

    // 접수 당시 검색 주제(대표 관심사) — 처리중 슬롯 제목으로 노출된다.
    @Column(length = 500)
    private String topic;

    @Column(name = "content_type", length = 100)
    private String contentType;

    // agent 202 body 의 job id — 파싱 실패 시 null 일 수 있어 참고용으로만 저장.
    @Column(name = "agent_job_id", length = 200)
    private String agentJobId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    // 이 접수가 만들어낸 카드의 public_id. 완료 전엔 null, 근사 매칭이 실패한 구 Snapshot 도 null.
    @Column(name = "card_public_id")
    private UUID cardPublicId;

    protected GenerationPending() {
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getReportType() {
        return reportType;
    }

    public String getTopic() {
        return topic;
    }

    public String getContentType() {
        return contentType;
    }

    public String getAgentJobId() {
        return agentJobId;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public UUID getCardPublicId() {
        return cardPublicId;
    }
}
