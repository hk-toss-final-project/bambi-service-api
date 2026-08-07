package com.bambi.service.wiki.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * agent 관심 Profile 조회 중계 응답 (GET /api/wiki/tags).
 *
 * <p>agent {@code InterestProfileResponse}(snake_case)를 그대로 읽어 camelCase 로 노출한다.
 * 팀 결정(07-29): agent가 자동 추출한 관심 키워드는 <b>tag</b>(해시태그 성격)로 명칭한다 →
 * agent 필드 {@code topic}·{@code interest_id} 를 {@code tag}·{@code tagId} 로 리네임해 내려준다.
 * (온보딩에서 사용자가 직접 고르는 "topic"과는 별개 개념 — 그건 service 직접 관심사)
 */
public record WikiTagsResponse(
        @JsonAlias("profile_id") String profileId,
        int version,
        String status,
        @JsonAlias("calculated_at") String calculatedAt,
        @JsonAlias("interests") List<WikiTag> tags) {

    /** 아직 활성 관심 Profile이 없는 사용자(agent 404) — 빈 목록으로 정규화한다. */
    public static WikiTagsResponse empty() {
        return new WikiTagsResponse(null, 0, "empty", null, List.of());
    }

    /**
     * 대표 관심사(태그) 1개 — score 가 가장 높은 태그를 <b>객체째</b> 돌려준다.
     *
     * <p>이름만 필요하면 {@link #topTopic()} 을 쓰면 되지만, agent 의 {@code INTEREST_BUNDLE}
     * 생성은 이름이 아니라 <b>{@code tagId}</b>(=agent {@code interest_id})를 요구한다.
     * 두 값을 같이 써야 하는 호출부가 태그를 두 번 찾지 않도록 여기서 한 번에 준다.
     *
     * <p>선택 기준은 {@link #topTopic()} 과 완전히 같다 — 같은 응답이면 두 메서드가 항상 같은
     * 태그를 가리킨다. 기준을 다르게 하면(예: tagId 없는 태그 제외) 스케줄러가 고르는 주제가
     * 조용히 바뀌므로 그렇게 하지 않는다. {@code tagId} 가 비어 올 가능성은 호출부가 다룬다.
     */
    public Optional<WikiTag> topTag() {
        if (tags == null) {
            return Optional.empty();
        }
        return tags.stream()
                .filter(t -> t.tag() != null && !t.tag().isBlank())
                .max(Comparator.comparingDouble(WikiTag::score));
    }

    /**
     * 생성 검색 주제로 쓸 대표 관심사(태그)의 <b>이름</b> 1개 — score 가 가장 높은 태그.
     * 계약상 생성 요청의 topic 은 라벨이 아니라 <b>실제 검색 주제</b>라, 고정 문구 대신 이 값을 넣는다
     * (유림 확인 08-05). 관심사가 없으면 empty → 호출부가 생성을 거절/건너뛴다.
     */
    public Optional<String> topTopic() {
        return topTag().map(WikiTag::tag);
    }
}
