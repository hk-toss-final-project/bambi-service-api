package com.bambi.service.follow.dto;

import java.util.UUID;

/**
 * 팔로워/팔로잉 목록 항목 — 프로필 화면의 팔로워/팔로잉 탭.
 * 순번 id 대신 publicId(UUID)로만 사용자를 노출한다(Card·프로필과 동일 컨벤션).
 * following = "지금 보고 있는 나"가 이 사용자를 팔로우 중인지 → 목록에서 바로 팔로우/언팔 토글용.
 * 게스트(비로그인) 열람이면 following 은 항상 false.
 */
public record FollowUserResponse(
        UUID publicId,
        String username,
        String displayName,
        boolean following) {
}
