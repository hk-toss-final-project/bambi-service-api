package com.bambi.service.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * agent-api 사용자 컨텍스트 upsert 요청 바디.
 * 계약: PUT /internal/v1/users/{userId}/context (agent UserContextUpsertRequest 와 1:1, snake_case).
 *
 * @param contextVersion         사용자별 단조 증가 정수. 같거나 작으면 agent 가 STALE_CONTEXT_VERSION 반환
 * @param plan                   요금제 (free | paid)
 * @param preferredLanguage      선호 언어 (기본 ko)
 * @param personalizationEnabled 개인화 사용 여부
 * @param blockedInterestIds     사용자가 차단(삭제)한 관심사 ID 목록
 * @param blockedSourceIds       사용자가 차단(삭제)한 소스 ID 목록
 */
public record AgentContextRequest(
        @JsonProperty("context_version") int contextVersion,
        @JsonProperty("plan") String plan,
        @JsonProperty("preferred_language") String preferredLanguage,
        @JsonProperty("personalization_enabled") boolean personalizationEnabled,
        @JsonProperty("blocked_interest_ids") List<String> blockedInterestIds,
        @JsonProperty("blocked_source_ids") List<String> blockedSourceIds) {

    /**
     * 지정 버전의 컨텍스트 요청. 필드 값은 현재 알려진 사용자 컨텍스트를 싣는다 —
     * 설정 기능(플랜·개인화·차단)이 붙기 전에는 기본값(무료·한국어·개인화 on·차단 없음)이고,
     * 붙으면 이 자리에서 저장된 사용자 설정으로 채우면 된다. 버전은 호출부가 단조 증가로 넘긴다.
     */
    public static AgentContextRequest forVersion(int version) {
        return new AgentContextRequest(version, "free", "ko", true, List.of(), List.of());
    }
}
