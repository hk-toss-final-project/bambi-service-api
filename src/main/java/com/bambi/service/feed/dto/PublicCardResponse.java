package com.bambi.service.feed.dto;

import com.bambi.service.card.Card;
import com.bambi.service.user.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 공개 피드 카드 응답 (SNS/Week2). 내 피드용 {@code CardResponse} 와 분리해 P0 회귀를 막는다.
 * 작성자(author)·좋아요 수·내 좋아요 여부는 서비스가 배치로 채워 넣는다(카드별 재조회 없음).
 * 식별자는 모두 publicId(UUID) — 순번 id 는 노출하지 않는다.
 */
public record PublicCardResponse(
        UUID publicId,
        String title,
        String summary,
        // why_for_you 는 폐기 방향(07-27) → 관심사 태그(tags)로 대체. 당분간 둘 다 내려 프론트 전환 여유를 준다.
        String whyForYou,
        List<String> tags,
        AuthorResponse author,
        long likeCount,
        boolean liked,
        boolean scrapped,
        // 추천 매칭(2026-08-11 계약 A안, 2단): 뷰어 관심 topic ∩ 카드 topic(정밀) / category ∩ 카드 topic의 category(넓음).
        // 서버가 뷰어 기준으로 계산. 게스트·비매칭·롤아웃 전 카드는 빈 목록. 프론트: matchedTopics 우선, 없으면 matchedCategories 로 추천 판정.
        List<MatchedTopic> matchedTopics,
        List<MatchedCategory> matchedCategories,
        List<SourceResponse> sources,
        OffsetDateTime createdAt) {

    public record AuthorResponse(UUID publicId, String username, String displayName) {

        static AuthorResponse from(User user) {
            if (user == null) {
                return new AuthorResponse(null, null, null);
            }
            return new AuthorResponse(user.getPublicId(), user.getUsername(), user.getDisplayName());
        }
    }

    public record SourceResponse(String title, String url) {
    }

    /** 매칭된 관심 토픽(taxonomy topic_key + 표시명). */
    public record MatchedTopic(String topicId, String name) {
    }

    /** 매칭된 관심 카테고리(taxonomy category_key + 표시명). */
    public record MatchedCategory(String categoryId, String name) {
    }

    public static PublicCardResponse from(Card card, User author, long likeCount,
                                          boolean liked, boolean scrapped,
                                          List<MatchedTopic> matchedTopics,
                                          List<MatchedCategory> matchedCategories) {
        List<SourceResponse> sources = card.getSources().stream()
                .map(s -> new SourceResponse(s.getTitle(), s.getUrl()))
                .toList();
        return new PublicCardResponse(
                card.getPublicId(),
                card.getTitle(),
                card.getSummary(),
                card.getWhyForYou(),
                List.copyOf(card.getInterestTags()),
                AuthorResponse.from(author),
                likeCount,
                liked,
                scrapped,
                matchedTopics == null ? List.of() : matchedTopics,
                matchedCategories == null ? List.of() : matchedCategories,
                sources,
                card.getCreatedAt());
    }
}
