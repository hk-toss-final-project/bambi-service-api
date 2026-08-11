package com.bambi.service.auth.dto;

import com.bambi.service.user.Role;
import com.bambi.service.user.User;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 사용자 노출용 요약 DTO. Entity 를 직접 응답에 노출하지 않는다.
 * 가입/로그인/me/프로필 편집이 같은 모양을 쓴다 — me 가 {id,email}만 주던 문제
 * (새로고침 시 헤더 이름이 "사용자"로 뜨는 버그)를 이 통일로 해결(07-31).
 * publicId 는 프론트가 "내 프로필" 링크(/users/{publicId})를 만들 때 쓴다.
 *
 * ⚠️ 본인 전용 DTO 다(auth me/login/signup · 프로필/설정 편집). 타인/관리자 노출에는 쓰지 않는다 —
 * 그래서 설정(defaultCardVisibility·reportReadyNotification)을 여기 얹어도 남에게 새지 않는다.
 * 설정 조회는 GET /api/auth/me 로 이 필드들을 읽는다(별도 GET 없이, 08-07).
 */
public record UserSummary(
        Long id,
        UUID publicId,
        String email,
        String username,
        String displayName,
        String bio,
        Set<String> roles,
        String defaultCardVisibility,
        boolean reportReadyNotification,
        boolean changeHistoryEnabled) {

    public static UserSummary from(User user) {
        return new UserSummary(
                user.getId(),
                user.getPublicId(),
                user.getEmail(),
                user.getUsername(),
                user.getDisplayName(),
                user.getBio(),
                user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()),
                user.getDefaultCardVisibility(),
                user.isReportReadyNotification(),
                user.isChangeHistoryEnabled());
    }
}
