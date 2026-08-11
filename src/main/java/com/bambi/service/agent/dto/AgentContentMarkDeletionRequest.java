package com.bambi.service.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 북마크 원본 연결 해제와 Agent Wiki 전체 재구성 요청. */
public record AgentContentMarkDeletionRequest(
        @JsonProperty("source_event_id") String sourceEventId,
        @JsonProperty("marked_source_event_id") String markedSourceEventId,
        @JsonProperty("content_id") String contentId) {
}
