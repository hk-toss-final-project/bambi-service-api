package com.bambi.service.card;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardRepository extends JpaRepository<Card, Long> {

    /**
     * 내 피드용 카드 목록 (최신순, soft delete 제외).
     * sources 를 @EntityGraph 로 1-shot 로딩해 N+1 을 차단한다.
     */
    @EntityGraph(attributePaths = "sources")
    List<Card> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);

    /**
     * 카드 단건 조회 (대외 식별자 publicId + 소유자 범위, soft delete 제외).
     * sources 를 함께 로딩(@EntityGraph). 없으면 empty → 컨트롤러가 404.
     * 남의 카드도 존재 노출 없이 empty(404) 로 처리된다.
     */
    @EntityGraph(attributePaths = "sources")
    Optional<Card> findByPublicIdAndUserIdAndDeletedAtIsNull(UUID publicId, Long userId);

    /** 발행 워커의 멱등 Upsert 판단용 — 같은 사용자+external_content_id 카드 존재 여부 */
    boolean existsByUserIdAndExternalContentId(Long userId, String externalContentId);

    /**
     * 발행 Upsert 대상 카드 조회 (같은 사용자+external_content_id).
     * 갱신 시 sources 를 교체하므로 함께 로딩(@EntityGraph)해 지연로딩 없이 다룬다.
     */
    @EntityGraph(attributePaths = "sources")
    Optional<Card> findByUserIdAndExternalContentId(Long userId, String externalContentId);

    // ── SNS(Week2) ─────────────────────────────────────────────

    /**
     * 소유자 검증 없이 대외 식별자(publicId)로 살아있는 카드 조회.
     * 카드 공개설정 변경(소유자 재확인은 서비스에서)·좋아요 대상 해석(PUBLIC 여부 확인)에 쓴다.
     */
    Optional<Card> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** 공개 프로필의 공개 카드 수. */
    long countByUserIdAndVisibilityAndDeletedAtIsNull(Long userId, String visibility);

    /**
     * 프로필 "최근 공개 시각" — 가장 최근 PUBLIC 카드의 생성 시각(공개 카드 없으면 null).
     * ⚠️ Card 에 발행-이벤트 타임스탬프가 없어 createdAt 으로 근사한다(공개 토글 시각 아님).
     */
    @Query("select max(c.createdAt) from Card c "
            + "where c.userId = :userId and c.visibility = 'PUBLIC' and c.deletedAt is null")
    OffsetDateTime findLastPublishedAt(@Param("userId") Long userId);

    /** 프로필 "이번 주 공개 개수" — since(=지금-7일) 이후 생성된 PUBLIC 카드 수(롤링 7일). */
    long countByUserIdAndVisibilityAndDeletedAtIsNullAndCreatedAtAfter(
            Long userId, String visibility, OffsetDateTime since);

    /** 이 리포트를 참조하는 특정 공개설정(PUBLIC 등) 카드가 살아있나 — 리포트 본문 접근 권한 판단용. */
    boolean existsByReportIdAndVisibilityAndDeletedAtIsNull(Long reportId, String visibility);

    /**
     * 공개 피드(전체) — PUBLIC 카드를 최신순으로.
     * 컬렉션(sources) fetch join + Pageable 은 LIMIT 을 SQL 로 못 내려 전량 로딩(HHH000104)하므로
     * 여기서는 @EntityGraph 를 쓰지 않는다. 페이지네이션(LIMIT)이 SQL 로 내려가 부분 인덱스
     * idx_cards_public_feed 가 살아나고, sources 는 Card.sources 의 @BatchSize 로 IN 배치 로딩된다.
     */
    @Query("select c from Card c where c.visibility = 'PUBLIC' and c.deletedAt is null "
            + "order by c.createdAt desc")
    List<Card> findPublicFeed(Pageable pageable);

    /** 공개 피드(팔로잉 스코프) — 내가 팔로우하는 작성자의 PUBLIC 카드만. (@BatchSize 로 sources 배치 로딩) */
    @Query("select c from Card c where c.visibility = 'PUBLIC' and c.deletedAt is null "
            + "and c.userId in :authorIds order by c.createdAt desc")
    List<Card> findPublicFeedByAuthors(@Param("authorIds") Collection<Long> authorIds, Pageable pageable);

    /**
     * 뷰어가 <b>좋아요</b>한 카드들의 taxonomy topic 집합 — 추천 선호 가중(2026-08-11 확정:
     * 선호 신호는 좋아요·북마크 2개만, agent 미전달·service 라이브 테이블 직접 참조).
     * 취소하면 행이 사라져 자동 차감, 기존 데이터는 자동 소급된다.
     */
    @Query("select distinct topic from Card c join c.taxonomyTopicIds topic, Like l "
            + "where l.cardId = c.id and l.userId = :userId and c.deletedAt is null")
    List<String> findLikedCardTopicIds(@Param("userId") Long userId);

    /** 뷰어가 <b>북마크(스크랩)</b>한 카드들의 taxonomy topic 집합 — 위와 동일 원칙. */
    @Query("select distinct topic from Card c join c.taxonomyTopicIds topic, Scrap s "
            + "where s.cardId = c.id and s.userId = :userId and c.deletedAt is null")
    List<String> findScrappedCardTopicIds(@Param("userId") Long userId);
}
