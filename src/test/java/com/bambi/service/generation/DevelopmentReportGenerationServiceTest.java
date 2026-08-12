package com.bambi.service.generation;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.generation.dto.GenerationRequest;
import com.bambi.service.generation.dto.GenerationTriggerResponse;
import com.bambi.service.wiki.AgentWikiClient;
import com.bambi.service.wiki.dto.WikiTag;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 개발용 아침·Wiki 관심사 즉시 생성 경로. */
class DevelopmentReportGenerationServiceTest {

    private final MorningBriefingGenerationService morningService =
            mock(MorningBriefingGenerationService.class);
    private final GenerationSubmissionService submissionService =
            mock(GenerationSubmissionService.class);
    private final AgentWikiClient wikiClient = mock(AgentWikiClient.class);
    // 기본 mock 은 false → Delta 꺼짐.
    private final ChangeHistorySettingReader changeHistorySettings =
            mock(ChangeHistorySettingReader.class);
    private final DevelopmentReportGenerationService service =
            new DevelopmentReportGenerationService(
                    morningService, submissionService, wikiClient, changeHistorySettings,
                    "interest_news_card");

    @Test
    void 아침_리포트는_공통_생성_서비스를_즉시_호출한다() {
        when(morningService.submit(eq(28L), any()))
                .thenReturn(Optional.of(new GenerationSubmissionService.Submission("pending-1", "job-1")));

        GenerationTriggerResponse response = service.generateMorning(28L);

        assertThat(response.id()).isEqualTo("pending-1");
        assertThat(response.agentJobId()).isEqualTo("job-1");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(morningService).submit(eq(28L), key.capture());
        assertThat(key.getValue()).startsWith("dev-morning-28-");
    }

    @Test
    void 아침_주제가_없으면_검증_오류로_거절한다() {
        when(morningService.submit(eq(28L), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateMorning(28L))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void 선택한_현재_Wiki_관심사를_INTEREST_BUNDLE로_접수한다() {
        String tagId = "33333333-3333-4333-8333-333333333333";
        when(wikiClient.getTags(28L)).thenReturn(tags(tagId, "반도체"));
        when(submissionService.submit(eq(28L), any(),
                eq(GenerationPendingService.REPORT_TYPE_WIKI_INTEREST), eq("반도체")))
                .thenReturn(new GenerationSubmissionService.Submission("pending-2", "job-2"));

        GenerationTriggerResponse response = service.generateWikiInterest(28L, tagId);

        assertThat(response.id()).isEqualTo("pending-2");
        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(submissionService).submit(eq(28L), request.capture(),
                eq(GenerationPendingService.REPORT_TYPE_WIKI_INTEREST), eq("반도체"));
        assertThat(request.getValue().generationScope()).isEqualTo("INTEREST_BUNDLE");
        assertThat(request.getValue().interestId()).isEqualTo(tagId);
        assertThat(request.getValue().idempotencyKey())
                .startsWith("dev-wiki-interest-28-" + tagId + "-");
    }

    @Test
    void 현재_활성_Wiki_관심사가_아니면_접수하지_않는다() {
        when(wikiClient.getTags(28L)).thenReturn(tags("active-id", "반도체"));

        assertThatThrownBy(() -> service.generateWikiInterest(28L, "retired-id"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getErrorCode())
                .isEqualTo(ErrorCode.INTEREST_NOT_FOUND);

        verify(submissionService, never()).submit(any(Long.class), any(), any(), any());
    }

    @Test
    void Wiki_관심사_리포트도_계정_설정을_그대로_싣는다() {
        // 🚨 2026-08-12 요구: 설정을 켜면 **모든 보고서**가 변경점 형식이다. 이 경로는 그전까지
        // interestBundle 팩토리에 파라미터조차 없어 설정과 무관하게 늘 꺼진 채 나갔다.
        when(changeHistorySettings.isEnabled(28L)).thenReturn(true);
        when(wikiClient.getTags(28L)).thenReturn(tags("tag-1", "반도체"));
        when(submissionService.submit(eq(28L), any(), any(), any()))
                .thenReturn(new GenerationSubmissionService.Submission("pending-1", "job-1"));

        service.generateWikiInterest(28L, "tag-1");

        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(submissionService).submit(eq(28L), request.capture(), any(), any());
        assertThat(request.getValue().changeHistoryEnabled()).isTrue();
        assertThat(request.getValue().idempotencyKey()).endsWith("-delta");
    }

    @Test
    void Wiki_관심사_리포트는_설정이_꺼지면_플래그를_싣지_않는다() {
        when(changeHistorySettings.isEnabled(28L)).thenReturn(false);
        when(wikiClient.getTags(28L)).thenReturn(tags("tag-1", "반도체"));
        when(submissionService.submit(eq(28L), any(), any(), any()))
                .thenReturn(new GenerationSubmissionService.Submission("pending-1", "job-1"));

        service.generateWikiInterest(28L, "tag-1");

        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(submissionService).submit(eq(28L), request.capture(), any(), any());
        assertThat(request.getValue().changeHistoryEnabled()).isNull();
        assertThat(request.getValue().idempotencyKey()).doesNotEndWith("-delta");
    }

    private static WikiTagsResponse tags(String tagId, String tag) {
        return new WikiTagsResponse("profile-1", 1, "active", null, List.of(
                new WikiTag(tagId, tag, "technology", 0.9, 0.8, List.of(), Map.of())));
    }
}
