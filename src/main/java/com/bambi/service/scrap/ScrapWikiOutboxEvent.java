package com.bambi.service.scrap;

import com.bambi.service.agent.dto.AgentAcceptedJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 스크랩 변경을 Agent Wiki에 최소 한 번 전달하는 DB Outbox 이벤트. */
@Entity
@Table(name = "scrap_wiki_outbox")
public class ScrapWikiOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "related_source_event_id", updatable = false)
    private UUID relatedSourceEventId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "card_id", nullable = false, updatable = false)
    private Long cardId;

    @Column(nullable = false, updatable = false, length = 10)
    private String action;

    @Column(name = "external_content_id", nullable = false, updatable = false, length = 200)
    private String externalContentId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "agent_job_id", length = 200)
    private String agentJobId;

    @Column(name = "source_document_id", length = 100)
    private String sourceDocumentId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    protected ScrapWikiOutboxEvent() {
    }

    private ScrapWikiOutboxEvent(
            UUID sourceEventId,
            UUID relatedSourceEventId,
            Long userId,
            Long cardId,
            String action,
            String externalContentId) {
        this.sourceEventId = sourceEventId;
        this.relatedSourceEventId = relatedSourceEventId;
        this.userId = userId;
        this.cardId = cardId;
        this.action = action;
        this.externalContentId = externalContentId;
        this.status = "PENDING";
        this.attemptCount = 0;
        this.nextAttemptAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    /** 스크랩 생성 Wiki 편입 이벤트를 만든다. */
    public static ScrapWikiOutboxEvent add(Long userId, Long cardId, String contentId) {
        return new ScrapWikiOutboxEvent(
                UUID.randomUUID(), null, userId, cardId, "ADD", contentId);
    }

    /** 스크랩 해제 이벤트를 원래 저장 이벤트와 연결해 만든다. */
    public static ScrapWikiOutboxEvent remove(
            Long userId, Long cardId, String contentId, UUID markedSourceEventId) {
        return new ScrapWikiOutboxEvent(
                UUID.randomUUID(), markedSourceEventId, userId, cardId, "REMOVE", contentId);
    }

    /** 폴러가 점유한 상태로 전환한다. */
    public void markProcessing() {
        status = "PROCESSING";
        attemptCount += 1;
        updatedAt = OffsetDateTime.now();
    }

    /** Agent 접수와 Wiki 상태 추적 등록이 끝난 이벤트를 종결한다. */
    public void markDelivered(AgentAcceptedJob accepted) {
        status = "DELIVERED";
        agentJobId = accepted.jobId();
        sourceDocumentId = accepted.sourceDocumentId();
        lastError = null;
        deliveredAt = OffsetDateTime.now();
        updatedAt = deliveredAt;
    }

    /** 실패 이벤트를 지수 지연 후 재시도 가능 상태로 돌린다. */
    public void markRetry(String error) {
        long delaySeconds = Math.min(300L, 1L << Math.min(attemptCount, 8));
        status = "PENDING";
        lastError = error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 2000));
        nextAttemptAt = OffsetDateTime.now().plusSeconds(delaySeconds);
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public UUID getSourceEventId() { return sourceEventId; }
    public UUID getRelatedSourceEventId() { return relatedSourceEventId; }
    public Long getUserId() { return userId; }
    public Long getCardId() { return cardId; }
    public String getAction() { return action; }
    public String getExternalContentId() { return externalContentId; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
}
