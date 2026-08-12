package com.bambi.service.generation;

import com.bambi.service.briefing.BriefingTopicService;
import com.bambi.service.generation.dto.GenerationRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link MorningBriefingGenerationService}의 준비 날짜 조회·생성 요청 전달을 검증한다. */
class MorningBriefingGenerationServiceTest {

    private final GenerationSubmissionService submissionService =
            mock(GenerationSubmissionService.class);
    private final BriefingTopicService briefingTopicService = mock(BriefingTopicService.class);
    // 기본 mock 은 false → Delta 꺼짐. 켠 경우는 각 테스트가 stub 한다.
    private final ChangeHistorySettingReader changeHistorySettings =
            mock(ChangeHistorySettingReader.class);
    private final MorningBriefingGenerationService service = new MorningBriefingGenerationService(
            submissionService, briefingTopicService, changeHistorySettings,
            "interest_news_card");

    @Test
    void 조회한_Snapshot_날짜를_생성_요청에도_그대로_보낸다() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(briefingTopicService.resolveForMorningBriefing(7L, date))
                .thenReturn(List.of("반도체", "프로야구"));
        when(submissionService.submit(eq(7L), any(), any(), any()))
                .thenReturn(new GenerationSubmissionService.Submission("pending-7", "job-7"));

        var result = service.submit(7L, "morning-7", date);

        assertThat(result).isPresent();
        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(submissionService).submit(
                eq(7L), request.capture(),
                eq(GenerationPendingService.REPORT_TYPE_MORNING_BRIEFING), eq("반도체"));
        assertThat(request.getValue().briefingDate()).isEqualTo(date);
        assertThat(request.getValue().topics()).containsExactly("반도체", "프로야구");
    }

    @Test
    void 지정일에_주제가_없으면_Agent_생성을_접수하지_않는다() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(briefingTopicService.resolveForMorningBriefing(7L, date)).thenReturn(List.of());

        assertThat(service.submit(7L, "morning-7", date)).isEmpty();

        verify(submissionService, never()).submit(any(Long.class), any(), any(), any());
    }

    @Test
    void 계정_설정이_켜지면_아침_브리핑에도_변경점_플래그를_싣는다() {
        // 🚨 2026-08-12 요구: 설정을 켜면 온디맨드뿐 아니라 **모든 보고서**가 변경점 형식이다.
        // 그전까지 이 경로는 값을 아예 안 실어 보내 설정이 무시됐다.
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(changeHistorySettings.isEnabled(7L)).thenReturn(true);
        when(briefingTopicService.resolveForMorningBriefing(7L, date))
                .thenReturn(List.of("반도체", "프로야구"));
        when(submissionService.submit(eq(7L), any(), any(), any()))
                .thenReturn(new GenerationSubmissionService.Submission("pending-7", "job-7"));

        service.submit(7L, "morning-7", date);

        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(submissionService).submit(eq(7L), request.capture(), any(), any());
        assertThat(request.getValue().changeHistoryEnabled()).isTrue();
        // topics 는 그대로 간다 — agent 가 주제마다 Delta 를 따로 돌려 합친다.
        assertThat(request.getValue().topics()).containsExactly("반도체", "프로야구");
    }

    @Test
    void 계정_설정이_꺼져_있으면_플래그를_아예_싣지_않는다() {
        // 꺼진 실행의 요청 본문은 지금까지와 바이트 단위로 같아야 한다(회귀 0).
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(changeHistorySettings.isEnabled(7L)).thenReturn(false);
        when(briefingTopicService.resolveForMorningBriefing(7L, date))
                .thenReturn(List.of("반도체"));
        when(submissionService.submit(eq(7L), any(), any(), any()))
                .thenReturn(new GenerationSubmissionService.Submission("pending-7", "job-7"));

        service.submit(7L, "morning-7", date);

        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(submissionService).submit(eq(7L), request.capture(), any(), any());
        assertThat(request.getValue().changeHistoryEnabled()).isNull();
    }

    @Test
    void 설정을_바꾸면_같은_날이어도_멱등키가_갈린다() {
        // 키가 같으면 agent 가 먼저 접수한 Job 을 돌려줘 "켰는데 안 바뀐다"가 된다.
        LocalDate date = LocalDate.of(2026, 8, 12);
        when(briefingTopicService.resolveForMorningBriefing(7L, date)).thenReturn(List.of("반도체"));
        when(submissionService.submit(eq(7L), any(), any(), any()))
                .thenReturn(new GenerationSubmissionService.Submission("pending-7", "job-7"));
        when(changeHistorySettings.isEnabled(7L)).thenReturn(false, true);

        service.submit(7L, "2026-08-12-7-interest_news_card", date);
        service.submit(7L, "2026-08-12-7-interest_news_card", date);

        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(submissionService, org.mockito.Mockito.times(2))
                .submit(eq(7L), request.capture(), any(), any());
        List<GenerationRequest> sent = request.getAllValues();
        assertThat(sent.get(0).idempotencyKey()).doesNotEndWith("-delta");
        assertThat(sent.get(1).idempotencyKey()).endsWith("-delta");
        assertThat(sent.get(0).idempotencyKey()).isNotEqualTo(sent.get(1).idempotencyKey());
    }
}
