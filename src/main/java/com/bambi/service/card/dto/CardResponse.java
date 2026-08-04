package com.bambi.service.card.dto;

import com.bambi.service.card.Card;
import com.bambi.service.user.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 카드 응답 DTO — Entity 직접 노출 금지.
 * 대외 식별자는 publicId(UUID) 만 노출한다 (내부 순번 id 숨김).
 * reportId = 이 카드의 본문(리포트) publicId. 프론트가 카드 상세 → 본문(GET /api/reports/{reportId})
 * 으로 이동하는 진입점. 동기 즉시 카드처럼 리포트가 없으면 null.
 *
 * 소셜 필드(author·likeCount·liked)는 **단건 상세(GET /api/cards/{publicId})에서만 채워진다** —
 * 상세 화면이 소유권·공개 여부·좋아요 초기 상태를 구성하는 용도(2026-08-04, 여진 요청).
 * 목록(내 피드)·저장 응답 등 다른 경로에서는 null 이다(배치 조회 없이 채우면 N+1 — 필요해지면
 * FeedService 배치 패턴으로 확장). visibility 는 카드 자체 값이라 모든 경로에서 채워진다.
 */
public record CardResponse(
        UUID publicId,
        UUID reportId,
        String title,
        String summary,
        String whyForYou,
        String visibility,
        AuthorResponse author,
        Long likeCount,
        Boolean liked,
        List<SourceResponse> sources,
        OffsetDateTime createdAt) {

    public record SourceResponse(String title, String url) {
    }

    /** 작성자 요약 — 공개피드 PublicCardResponse.AuthorResponse 와 같은 모양(publicId·username·displayName). */
    public record AuthorResponse(UUID publicId, String username, String displayName) {

        public static AuthorResponse from(User user) {
            if (user == null) {
                return new AuthorResponse(null, null, null);
            }
            return new AuthorResponse(user.getPublicId(), user.getUsername(), user.getDisplayName());
        }
    }

    /** 리포트 없는(또는 참조 불필요한) 카드 — reportId=null. 소셜 필드 null(목록·저장 응답용). */
    public static CardResponse from(Card card) {
        return from(card, null);
    }

    /** reportPublicId = 이 카드가 참조하는 리포트의 publicId(없으면 null). 소셜 필드 null(목록·저장 응답용). */
    public static CardResponse from(Card card, UUID reportPublicId) {
        return new CardResponse(
                card.getPublicId(),
                reportPublicId,
                card.getTitle(),
                card.getSummary(),
                card.getWhyForYou(),
                card.getVisibility(),
                null,
                null,
                null,
                sourcesOf(card),
                card.getCreatedAt());
    }

    /** 단건 상세용 — 작성자·좋아요까지 채운다. liked 는 게스트(viewer 없음)면 false. */
    public static CardResponse forDetail(Card card, UUID reportPublicId,
                                         User author, long likeCount, boolean liked) {
        return new CardResponse(
                card.getPublicId(),
                reportPublicId,
                card.getTitle(),
                card.getSummary(),
                card.getWhyForYou(),
                card.getVisibility(),
                AuthorResponse.from(author),
                likeCount,
                liked,
                sourcesOf(card),
                card.getCreatedAt());
    }

    private static List<SourceResponse> sourcesOf(Card card) {
        return card.getSources().stream()
                .map(s -> new SourceResponse(s.getTitle(), s.getUrl()))
                .toList();
    }
}
