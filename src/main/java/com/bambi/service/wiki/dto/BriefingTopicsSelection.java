package com.bambi.service.wiki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * agent 가 개인 Wiki 맥락을 읽어 고른 아침 브리핑 주제
 * ({@code GET /internal/v1/users/{id}/briefing-topics}, 2026-08-11 계약).
 *
 * <p><b>관심사 점수 상위 N개가 아니다.</b> 연결 수 상위를 그대로 쓰면 도구·출처가 주제가 되므로
 * (실측 {@code DBeaver Community} 1.00), agent 가 후보를 넓게 받아 맥락을 읽고 고른다.
 *
 * <p>{@code topics} 가 비면 <b>위키가 없거나 고를 만한 주제가 없는 사용자</b>다. 오류가 아니라
 * 정상 상태이며, 호출부는 등록 관심사 폴백으로 넘어간다.
 *
 * <p>{@code reason} 은 agent 의 선정 근거다 — <b>로그·디버깅용이고 사용자에게 보이지 않는다.</b>
 * 아침 브리핑이 왜 그 주제를 골랐는지는 결과만 봐서는 알 수 없어서, 실패 조사 때 이 값이 필요하다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BriefingTopicsSelection(
        @JsonProperty("topics") List<String> topics,
        @JsonProperty("reason") String reason,
        @JsonProperty("candidate_count") Integer candidateCount) {

    public static BriefingTopicsSelection empty() {
        return new BriefingTopicsSelection(List.of(), "", 0);
    }

    /** 고른 주제 — 공백·빈 값을 걸러 반환한다. 응답에 필드가 없어도 빈 목록이다. */
    public List<String> normalizedTopics() {
        if (topics == null) {
            return List.of();
        }
        return topics.stream()
                .filter(topic -> topic != null && !topic.isBlank())
                .map(String::strip)
                .toList();
    }

    /** 로그용 근거 — 없으면 빈 문자열. */
    public String reasonOrEmpty() {
        return reason == null ? "" : reason;
    }
}
