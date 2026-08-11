package com.bambi.service.user.dto;

/**
 * 사용자 설정 변경(PATCH /api/users/me/settings) — 부분 업데이트.
 * 모든 필드 선택적(null = 미변경). defaultCardVisibility 값 검증(PRIVATE/PUBLIC)은 서비스에서 한다.
 * changeHistoryEnabled = 변경점(Delta) 추적 계정 설정(V22, 김기용 08-10) — 요청 단위 토글 대체.
 */
public record UpdateSettingsRequest(
        String defaultCardVisibility,
        Boolean reportReadyNotification,
        Boolean changeHistoryEnabled) {
}
