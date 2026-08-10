package com.bambi.service.generation.dto;

import jakarta.validation.constraints.Size;

/**
 * 즉시 생성 요청 body (POST /api/reports/generate) — 2026-08-06 계약(여진·소라·우석).
 * topic = 사용자가 홈 rail 에서 고른 관심사 이름. 온보딩 관심사(service)와 LLM Wiki 관심사(agent 태그)의
 * ID 체계가 달라 둘을 통일하는 값은 이름 문자열이다. 길이 제약은 관심사 name(1~100자)과 동일.
 *
 * <p>body 자체가 optional 이다 — 없거나 topic 이 비어 있으면 기존처럼 대표 관심사(score 최고)를
 * 자동 선택한다(하위호환). topic 이 오면 서버가 관심사 원자 처리(없으면 USER 로 추가) 후 생성한다.
 */
public record GenerationTriggerRequest(
        @Size(max = 100) String topic,
        Boolean changeHistoryEnabled,
        @Size(max = 64) String interestTagId) {

    /** 정규화된 topic — 앞뒤 공백 제거, 비어 있으면 null(대표 관심사 자동 선택 신호). */
    public String normalizedTopic() {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        return topic.strip();
    }

    /**
     * 변경점(Delta) 추적 요청 여부 (agent-api #12 김기용). 생략하면 꺼짐 —
     * <b>기존 호출자는 이 필드를 모르므로 기본이 꺼짐이어야 동작이 안 바뀐다.</b>
     */
    public boolean wantsChangeHistory() {
        return Boolean.TRUE.equals(changeHistoryEnabled);
    }

    /**
     * 관심사 깊게 파기(범주 리포트) 요청 여부 (2026-08-10 우석·기용). {@code interestTagId} =
     * {@code GET /api/wiki/tags} 의 {@code tagId}. 생략하면 기존 온디맨드 경로 그대로다.
     *
     * <p>topic·Delta 와 조합 규칙은 컨트롤러가 검증한다 — 깊게 파기는 루트를 agent 가 정하므로
     * topic 과 배타이고, Delta 조합은 계약에 정의가 없어 거절한다.
     */
    public boolean wantsDeepDive() {
        return interestTagId != null && !interestTagId.isBlank();
    }

    /** 정규화된 태그 ID — 앞뒤 공백 제거. {@link #wantsDeepDive()} 가 true 일 때만 의미 있다. */
    public String normalizedInterestTagId() {
        return interestTagId == null ? null : interestTagId.strip();
    }
}
