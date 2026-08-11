package com.bambi.service.card.dto;

import com.bambi.service.card.Card;
import com.bambi.service.report.Report;
import com.bambi.service.report.dto.ReportCoverImageResponse;
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
 *
 * reportType = 생성 유형(MORNING_BRIEFING|ON_DEMAND, nullable) — 발행 시점에 카드에 복제 저장된
 * 카드 자체 값이라 모든 경로에서 채워진다. 기존 생성분·즉시 카드·롤아웃 전 발행은 null.
 */
public record CardResponse(
        UUID publicId,
        UUID reportId,
        ReportCoverImageResponse coverImage,
        String title,
        String summary,
        String whyForYou,
        List<String> tags,
        String visibility,
        String reportType,
        AuthorResponse author,
        Long likeCount,
        Boolean liked,
        Boolean scrapped,
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
        // 캐스팅은 from(Card, Report) 오버로드와의 모호성 해소용이다(null 리터럴은 둘 다 맞는다).
        return from(card, (UUID) null);
    }

    /**
     * 목록용 — 리포트를 통째로 받아 publicId 와 <b>대표 이미지</b>를 함께 채운다. 소셜 필드는 null.
     *
     * <p>리포트가 없는(즉시) 카드는 {@code report=null} 이고 둘 다 null 이 된다.
     *
     * <p><b>N+1 이 아니다</b> — 호출부({@code FeedService.myFeed})가 이미 리포트를 한 번의
     * IN 쿼리로 배치 로딩해 두고 publicId 만 꺼내 쓰고 있었다(2026-08-11 확인). 같은 객체에서
     * 커버 이미지도 꺼낼 뿐이라 쿼리는 늘지 않는다.
     */
    public static CardResponse from(Card card, Report report) {
        return from(card,
                report != null ? report.getPublicId() : null,
                ReportCoverImageResponse.from(report));
    }

    /** reportPublicId = 이 카드가 참조하는 리포트의 publicId(없으면 null). 소셜 필드 null(목록·저장 응답용). */
    public static CardResponse from(Card card, UUID reportPublicId) {
        return from(card, reportPublicId, null);
    }

    private static CardResponse from(Card card, UUID reportPublicId,
                                     ReportCoverImageResponse coverImage) {
        return new CardResponse(
                card.getPublicId(),
                reportPublicId,
                coverImage,
                card.getTitle(),
                card.getSummary(),
                card.getWhyForYou(),
                tagsOf(card),
                card.getVisibility(),
                card.getReportType(),
                null,
                null,
                null,
                null,
                sourcesOf(card),
                card.getCreatedAt());
    }

    /** 단건 상세용 — 작성자·좋아요·스크랩까지 채운다. liked·scrapped 는 게스트(viewer 없음)면 false. */
    public static CardResponse forDetail(Card card, Report report,
                                         User author, long likeCount, boolean liked, boolean scrapped) {
        return new CardResponse(
                card.getPublicId(),
                report != null ? report.getPublicId() : null,
                ReportCoverImageResponse.from(report),
                card.getTitle(),
                card.getSummary(),
                card.getWhyForYou(),
                tagsOf(card),
                card.getVisibility(),
                card.getReportType(),
                AuthorResponse.from(author),
                likeCount,
                liked,
                scrapped,
                sourcesOf(card),
                card.getCreatedAt());
    }

    private static List<SourceResponse> sourcesOf(Card card) {
        return card.getSources().stream()
                .map(s -> new SourceResponse(s.getTitle(), s.getUrl()))
                .toList();
    }

    /** 카드 관심사 태그(agent 발행 topic). 리포트/발행 없는 즉시 카드는 빈 목록. */
    private static List<String> tagsOf(Card card) {
        return List.copyOf(card.getInterestTags());
    }
}
