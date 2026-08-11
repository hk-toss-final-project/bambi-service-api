package com.bambi.service.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Agent 비동기 작업 202 접수 응답에서 상태 추적에 필요한 최소 필드. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentAcceptedJob(
        @JsonProperty("job_id") String jobId,
        String status,
        @JsonProperty("source_document_id") String sourceDocumentId,
        @JsonProperty("source_document_version_id") String sourceDocumentVersionId) {

    /** 원본 식별자가 필요 없는 기존 호출부를 위한 호환 생성자. */
    public AgentAcceptedJob(String jobId, String status) {
        this(jobId, status, null, null);
    }
}
