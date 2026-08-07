package com.bambi.service.wiki.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WikiTagsResponse#topTag()} · {@link WikiTagsResponse#topTopic()} 검증.
 *
 * 두 메서드가 항상 같은 태그를 가리켜야 한다 — 기준이 갈리면 스케줄러가 고르는 검색 주제와
 * INTEREST_BUNDLE 이 쓰는 관심사 id 가 서로 다른 태그를 가리키게 된다.
 */
class WikiTagsResponseTest {

    private WikiTag tag(String tagId, String name, double score) {
        return new WikiTag(tagId, name, "concept", score, 0.9, List.of(), null);
    }

    private WikiTagsResponse of(WikiTag... tags) {
        return new WikiTagsResponse("profile-1", 3, "active", "2026-08-07T00:00:00Z", List.of(tags));
    }

    @Test
    @DisplayName("대표 태그는 score 최고 — 목록 순서와 무관하다")
    void topTagPicksHighestScoreRegardlessOfOrder() {
        WikiTagsResponse response = of(
                tag("t-1", "주가변동", 0.98),
                tag("t-2", "SK하이닉스", 1.0),
                tag("t-3", "삼성전자", 0.95));

        assertThat(response.topTag()).isPresent();
        assertThat(response.topTag().get().tag()).isEqualTo("SK하이닉스");
        assertThat(response.topTag().get().tagId()).isEqualTo("t-2");
    }

    @Test
    @DisplayName("topTag 와 topTopic 은 같은 태그를 가리킨다")
    void topTagAndTopTopicAgree() {
        WikiTagsResponse response = of(
                tag("t-1", "ADR상장", 0.91),
                tag("t-2", "SK하이닉스", 1.0));

        assertThat(response.topTopic()).contains(response.topTag().orElseThrow().tag());
    }

    @Test
    @DisplayName("이름이 비어 있는 태그는 대표에서 제외한다 — 검색어로 쓸 수 없다")
    void blankNamedTagIsNotRepresentative() {
        WikiTagsResponse response = of(
                tag("t-1", "   ", 1.0),
                tag("t-2", "SK하이닉스", 0.5));

        assertThat(response.topTag().orElseThrow().tag()).isEqualTo("SK하이닉스");
    }

    @Test
    @DisplayName("tagId 가 없어도 대표 선택 기준은 바뀌지 않는다 — 쓸지 말지는 호출부가 정한다")
    void missingTagIdDoesNotChangeSelection() {
        WikiTagsResponse response = of(
                tag(null, "SK하이닉스", 1.0),
                tag("t-2", "삼성전자", 0.95));

        assertThat(response.topTag().orElseThrow().tag()).isEqualTo("SK하이닉스");
        assertThat(response.topTag().orElseThrow().tagId()).isNull();
        assertThat(response.topTopic()).contains("SK하이닉스");
    }

    @Test
    @DisplayName("관심사가 없으면 둘 다 empty")
    void emptyProfileGivesEmpty() {
        assertThat(WikiTagsResponse.empty().topTag()).isEmpty();
        assertThat(WikiTagsResponse.empty().topTopic()).isEmpty();
    }
}
