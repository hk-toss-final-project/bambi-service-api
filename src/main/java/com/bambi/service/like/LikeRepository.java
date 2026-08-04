package com.bambi.service.like;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LikeRepository extends JpaRepository<Like, LikeId> {

    long countByCardId(Long cardId);

    /** 카드 단건 상세용 — 이 사용자가 이 카드에 좋아요를 눌렀는지. */
    boolean existsByUserIdAndCardId(Long userId, Long cardId);

    /**
     * 멱등 좋아요 — 이미 눌렀으면 조용히 무시(ON CONFLICT DO NOTHING).
     * 낙관적 업데이트 더블탭/재시도에도 유니크 위반 없이 안전.
     */
    @Modifying
    @Query(value = "INSERT INTO service.likes(user_id, card_id) "
            + "VALUES (:userId, :cardId) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIgnore(@Param("userId") Long userId, @Param("cardId") Long cardId);

    /** 좋아요 취소 — 없어도 0건, 멱등. */
    @Modifying
    @Query("delete from Like l where l.userId = :userId and l.cardId = :cardId")
    int deleteRelation(@Param("userId") Long userId, @Param("cardId") Long cardId);

    /** 공개피드 배치용 — 카드별 좋아요 수를 한 번의 group by 로 (N+1 방지). */
    @Query("select l.cardId as cardId, count(l) as count from Like l "
            + "where l.cardId in :cardIds group by l.cardId")
    List<CardLikeCount> countByCardIds(@Param("cardIds") Collection<Long> cardIds);

    /** 공개피드 배치용 — 이 카드들 중 내가 좋아요한 카드 id (한 번의 IN 쿼리). */
    @Query("select l.cardId from Like l where l.userId = :userId and l.cardId in :cardIds")
    List<Long> findLikedCardIds(@Param("userId") Long userId, @Param("cardIds") Collection<Long> cardIds);

    /** group by 결과 투영. */
    interface CardLikeCount {
        Long getCardId();

        long getCount();
    }
}
