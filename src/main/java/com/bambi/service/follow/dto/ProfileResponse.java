package com.bambi.service.follow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 공개 프로필 — 팔로우 버튼/프로필 헤더용. 순번 id 대신 publicId 로만 사용자를 노출한다.
 * following = "지금 보고 있는 나"가 이 사용자를 팔로우 중인지.
 * bio·joinedAt(가입 시점)은 프로필 화면 헤더용(V8, 07-31).
 * lastPublishedAt·weekPublicCount 는 프로필 통계(여진 목업, 08-07):
 *  - lastPublishedAt = 가장 최근 PUBLIC 카드 생성 시각(공개 카드 없으면 null).
 *    ⚠️ 발행-이벤트 타임스탬프가 없어 createdAt 근사(공개 토글 시각 아님).
 *  - weekPublicCount = 최근 7일 내 생성된 PUBLIC 카드 수(롤링 7일).
 */
public record ProfileResponse(
        UUID publicId,
        String username,
        String displayName,
        String bio,
        OffsetDateTime joinedAt,
        long followerCount,
        long followingCount,
        boolean following,
        long publicCardCount,
        OffsetDateTime lastPublishedAt,
        long weekPublicCount) {
}
