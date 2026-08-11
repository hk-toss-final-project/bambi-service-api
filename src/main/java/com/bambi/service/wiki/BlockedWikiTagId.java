package com.bambi.service.wiki;

import java.io.Serializable;
import java.util.Objects;

/** {@link BlockedWikiTag} 복합키 (user_id, tag_name). */
public class BlockedWikiTagId implements Serializable {

    private Long userId;
    private String tagName;

    public BlockedWikiTagId() {
    }

    public BlockedWikiTagId(Long userId, String tagName) {
        this.userId = userId;
        this.tagName = tagName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockedWikiTagId other)) {
            return false;
        }
        return Objects.equals(userId, other.userId) && Objects.equals(tagName, other.tagName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tagName);
    }
}
