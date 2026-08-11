package com.bambi.service.scrap;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 스크랩 Wiki Outbox의 순서 보장 Claim과 원본 저장 이벤트 조회를 담당한다. */
public interface ScrapWikiOutboxRepository extends JpaRepository<ScrapWikiOutboxEvent, Long> {

    @Query(value = """
            SELECT event.*
            FROM service.scrap_wiki_outbox AS event
            WHERE (
                (event.status = 'PENDING' AND event.next_attempt_at <= now())
                OR (event.status = 'PROCESSING' AND event.updated_at < now() - interval '5 minutes')
            )
              AND NOT EXISTS (
                  SELECT 1
                  FROM service.scrap_wiki_outbox AS earlier
                  WHERE earlier.user_id = event.user_id
                    AND earlier.card_id = event.card_id
                    AND earlier.id < event.id
                    AND earlier.status <> 'DELIVERED'
              )
            ORDER BY event.id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ScrapWikiOutboxEvent> findClaimable(@Param("limit") int limit);

    Optional<ScrapWikiOutboxEvent> findFirstByUserIdAndCardIdAndActionOrderByIdDesc(
            Long userId, Long cardId, String action);

    Optional<ScrapWikiOutboxEvent> findBySourceEventId(UUID sourceEventId);
}
