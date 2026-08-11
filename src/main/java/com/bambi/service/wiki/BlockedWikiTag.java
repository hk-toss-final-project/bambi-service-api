package com.bambi.service.wiki;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 사용자가 [AI가 최근 발견한 관심사]에서 숨긴 태그 (V27, 2026-08-11).
 *
 * <p>키는 id 가 아니라 <b>정규화된 이름</b>이다 — agent 태그 id 는 위키 재계산마다 새로 발급돼
 * 다음 빌드에서 같은 주제가 다른 id 로 돌아오기 때문이다. 정규화는 {@link #normalize} 한 곳에서만 한다.
 */
@Entity
@Table(name = "blocked_wiki_tags")
@IdClass(BlockedWikiTagId.class)
public class BlockedWikiTag {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "tag_name", nullable = false, length = 200)
    private String tagName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected BlockedWikiTag() {
    }

    public BlockedWikiTag(Long userId, String tagName) {
        this.userId = userId;
        this.tagName = tagName;
    }

    /** 대소문자·앞뒤 공백 차이로 숨김이 새지 않게 한 곳에서 정규화한다(프론트 이름 대조와 같은 규칙). */
    public static String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase();
    }

    public Long getUserId() {
        return userId;
    }

    public String getTagName() {
        return tagName;
    }
}
