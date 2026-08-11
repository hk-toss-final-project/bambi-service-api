package com.bambi.service.wiki;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 숨긴 발견 관심사 저장소. 추가·해제 모두 멱등하게 다룬다. */
public interface BlockedWikiTagRepository extends JpaRepository<BlockedWikiTag, BlockedWikiTagId> {

    @Query("select b.tagName from BlockedWikiTag b where b.userId = :userId")
    List<String> findNamesByUserId(@Param("userId") Long userId);

    /** 이미 숨겨져 있어도 예외 없이 통과(멱등) — 좋아요·스크랩과 같은 규칙. */
    @Modifying
    @Query(value = """
            INSERT INTO service.blocked_wiki_tags (user_id, tag_name)
            VALUES (:userId, :tagName)
            ON CONFLICT (user_id, tag_name) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(@Param("userId") Long userId, @Param("tagName") String tagName);

    /** 숨김 해제 — 없어도 0건(멱등). */
    @Modifying
    @Query("delete from BlockedWikiTag b where b.userId = :userId and b.tagName = :tagName")
    int deleteByUserIdAndTagName(@Param("userId") Long userId, @Param("tagName") String tagName);
}
