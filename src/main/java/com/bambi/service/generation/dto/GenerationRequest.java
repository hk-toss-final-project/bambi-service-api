package com.bambi.service.generation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 콘텐츠 생성 요청 (agent 계약: POST /internal/v1/users/{id}/generations, §3.4).
 * userId 는 경로에 있으므로 바디에 없다. agent 와 1:1 (snake_case).
 *
 * @param idempotencyKey {날짜윈도우}-{userId}-{contentType} 규칙 — 스케줄러 재시도·중복 실행에도 Job 1개.
 * @param topic          생성 주제 (1~500자, 필수).
 * @param contentType    기본 interest_news_card.
 * @param language       생략 시 컨텍스트의 선호 언어.
 * @param scheduledAt    실행 예약 시각. **시간대 필수** (예: 2026-07-30T07:00:00+09:00). null 이면 즉시 실행 대상.
 * @param reportType     생성 유형 (2026-08-06 계약: MORNING_BRIEFING | ON_DEMAND | ONBOARDING).
 *                       agent 가 발행 snapshot(PublishItem)에 그대로 실어 돌려주고, service 가 claim 에서 저장한다.
 *                       ONBOARDING 은 agent 자동 경로라 service 트리거는 앞 2개만 보낸다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)   // language/scheduled_at/report_type null 은 직렬화 생략
public record GenerationRequest(
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("topic") String topic,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("language") String language,
        @JsonProperty("scheduled_at") OffsetDateTime scheduledAt,
        @JsonProperty("report_type") String reportType) {
}
