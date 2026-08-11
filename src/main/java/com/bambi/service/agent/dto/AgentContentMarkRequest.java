package com.bambi.service.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 북마크한 카드의 Agent Wiki 편입 요청. */
public record AgentContentMarkRequest(
        @JsonProperty("source_event_id") String sourceEventId,
        @JsonProperty("content_id") String contentId) {
}
