package com.bambi.service.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** {@link NotificationService}의 리포트 완료 알림 계약을 검증한다. */
class NotificationServiceTest {

    @Test
    void 리포트_완료_알림은_멱등키와_리포트_경로를_저장한다() {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationService service = new NotificationService(repository);
        UUID reportId = UUID.randomUUID();

        service.notifyReportReady(7L, "content-1", 2, "제목", "요약", reportId, "MORNING_BRIEFING");

        verify(repository).insertReportReady(
                7L,
                "report-ready:content-1:v2",
                "새 리포트가 준비됐어요: 제목",
                "요약",
                "/report/" + reportId,
                "MORNING_BRIEFING");
    }

    @Test
    void 생성_유형이_없는_발행도_알림은_그대로_만든다() {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationService service = new NotificationService(repository);
        UUID reportId = UUID.randomUUID();

        service.notifyReportReady(7L, "content-1", 1, "제목", "요약", reportId, null);   // 롤아웃 전 관용

        verify(repository).insertReportReady(
                eq(7L), eq("report-ready:content-1:v1"), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull());
    }

    // ---- 팔로우 알림 (2026-08-11 여진 요청) ------------------------------------

    @Test
    void 팔로우_알림은_팔로워별_멱등키와_프로필_경로를_저장한다() {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationService service = new NotificationService(repository);
        UUID followerPublicId = UUID.randomUUID();

        service.notifyFollowed(7L, 3L, "파라미", followerPublicId);

        verify(repository).insertFollow(
                7L,
                "follow:3",                                   // 같은 사람 재팔로우 = 알림 1번만
                "파라미님이 나를 팔로우하기 시작했어요",
                "/users/" + followerPublicId);
    }

    @Test
    void 팔로워_표시명이_비면_사용자로_대체한다() {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationService service = new NotificationService(repository);

        service.notifyFollowed(7L, 3L, "  ", UUID.randomUUID());

        verify(repository).insertFollow(
                eq(7L), eq("follow:3"),
                eq("사용자님이 나를 팔로우하기 시작했어요"), anyString());
    }

    @Test
    void 긴_Agent_문자열은_알림_컬럼_길이에_맞춰_제한한다() {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationService service = new NotificationService(repository);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);

        service.notifyReportReady(
                7L, "content-1", 1, "가".repeat(500), "나".repeat(700), UUID.randomUUID(), null);

        verify(repository).insertReportReady(
                eq(7L), anyString(), title.capture(), body.capture(), anyString(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(title.getValue()).hasSize(200);
        assertThat(body.getValue()).hasSize(500);
    }
}
