package com.bambi.service.wiki.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WikiTagsResponse#topTag()} · {@link WikiTagsResponse#topTopic()} 검증.
 *
 * 두 메서드가 항상 같은 태그를 가리켜야 한다 — 기준이 갈리면 이름과 tagId 를 함께 쓰는
 * 호출부가 서로 다른 태그를 가리키게 된다.
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

    @Test
    @DisplayName("상위 N개는 score 내림차순으로 잘라 준다 — 아침 브리핑 topics[] 용")
    void topTagsReturnsHighestScoresInOrder() {
        WikiTagsResponse response = of(
                tag("t-1", "삼성전자", 0.95),
                tag("t-2", "SK하이닉스", 1.0),
                tag("t-3", "KT", 0.85),
                tag("t-4", "주가변동", 0.98));

        assertThat(response.topTags(3))
                .extracting(WikiTag::tag)
                .containsExactly("SK하이닉스", "주가변동", "삼성전자");
    }

    @Test
    @DisplayName("가진 것보다 많이 달라고 하면 있는 만큼만 준다")
    void topTagsReturnsWhatItHas() {
        WikiTagsResponse response = of(tag("t-1", "SK하이닉스", 1.0));

        assertThat(response.topTags(5)).hasSize(1);
    }

    @Test
    @DisplayName("limit 이 0 이하면 빈 목록")
    void nonPositiveLimitGivesEmptyList() {
        WikiTagsResponse response = of(tag("t-1", "SK하이닉스", 1.0));

        assertThat(response.topTags(0)).isEmpty();
        assertThat(response.topTags(-1)).isEmpty();
    }

    @Test
    @DisplayName("동점이면 응답에 먼저 온 태그가 앞선다 — 같은 응답이면 결과도 항상 같다")
    void tiesKeepResponseOrder() {
        WikiTagsResponse response = of(
                tag("t-1", "먼저", 0.9),
                tag("t-2", "나중", 0.9));

        assertThat(response.topTags(2))
                .extracting(WikiTag::tag)
                .containsExactly("먼저", "나중");
    }

    @Test
    @DisplayName("topTag 는 topTags(1) 의 첫 원소와 같다 — 기준이 갈리지 않는다")
    void topTagIsFirstOfTopTags() {
        WikiTagsResponse response = of(
                tag("t-1", "주가변동", 0.98),
                tag("t-2", "SK하이닉스", 1.0));

        assertThat(response.topTag()).contains(response.topTags(1).get(0));
    }
}
