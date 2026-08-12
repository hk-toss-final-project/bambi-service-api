package com.bambi.service.generation;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.generation.dto.GenerationRequest;
import com.bambi.service.generation.dto.GenerationTriggerResponse;
import com.bambi.service.wiki.AgentWikiClient;
import com.bambi.service.wiki.dto.WikiTag;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 개발 서버에서 실제 아침·Wiki 관심사 리포트 생성 경로를 즉시 검증하는 오케스트레이터.
 * 요청 대상은 인증 사용자 본인으로 한정되며 생성 결과는 기존 펜딩·발행·알림 흐름을 그대로 탄다.
 */
@Service
public class DevelopmentReportGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentReportGenerationService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MorningBriefingGenerationService morningBriefingGenerationService;
    private final GenerationSubmissionService submissionService;
    private final AgentWikiClient wikiClient;
    private final ChangeHistorySettingReader changeHistorySettings;
    private final String contentType;

    public DevelopmentReportGenerationService(
            MorningBriefingGenerationService morningBriefingGenerationService,
            GenerationSubmissionService submissionService,
            AgentWikiClient wikiClient,
            ChangeHistorySettingReader changeHistorySettings,
            @Value("${app.scheduler.generation.content-type:interest_news_card}") String contentType) {
        this.morningBriefingGenerationService = morningBriefingGenerationService;
        this.submissionService = submissionService;
        this.wikiClient = wikiClient;
        this.changeHistorySettings = changeHistorySettings;
        this.contentType = contentType;
    }

    /** 실제 아침 브리핑 경로를 예약 없이 즉시 접수한다. */
    public GenerationTriggerResponse generateMorning(long userId) {
        GenerationSubmissionService.Submission submission = morningBriefingGenerationService
                .submit(userId, minuteKey("dev-morning", userId, null))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_ERROR, "생성할 아침 브리핑 관심사가 없습니다."));
        log.info("[DevelopmentReportGeneration] 아침 리포트 즉시 생성 userId={}, pendingId={}, agentJobId={}",
                userId, submission.pendingId(), submission.agentJobId());
        return GenerationTriggerResponse.accepted(submission.pendingId(), submission.agentJobId());
    }

    /** 현재 활성 Wiki 관심사를 확인한 뒤 명시적인 INTEREST_BUNDLE 리포트를 즉시 접수한다. */
    public GenerationTriggerResponse generateWikiInterest(long userId, String requestedTagId) {
        String tagId = requestedTagId == null ? "" : requestedTagId.strip();
        WikiTag selected = currentTags(userId).stream()
                .filter(tag -> tag.tagId() != null && tag.tagId().equals(tagId))
                .filter(tag -> tag.tag() != null && !tag.tag().isBlank())
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTEREST_NOT_FOUND, "현재 활성 Wiki 관심사가 아닙니다."));

        // 계정 설정을 그대로 싣는다 — 설정을 켜면 모든 보고서가 변경점 형식이어야 한다
        // (2026-08-12 여진 요구). 이 경로는 그전까지 팩토리에 파라미터조차 없어 늘 꺼져 있었다.
        boolean changeHistory = changeHistorySettings.isEnabled(userId);
        GenerationRequest request = GenerationRequest.interestBundle(
                MorningBriefingGenerationService.deltaAwareKey(
                        minuteKey("dev-wiki-interest", userId, tagId), changeHistory),
                tagId,
                contentType,
                GenerationPendingService.REPORT_TYPE_WIKI_INTEREST,
                changeHistory);
        GenerationSubmissionService.Submission submission = submissionService.submit(
                userId,
                request,
                GenerationPendingService.REPORT_TYPE_WIKI_INTEREST,
                selected.tag().strip());
        log.info("[DevelopmentReportGeneration] Wiki 관심사 리포트 즉시 생성 "
                        + "userId={}, tagId={}, pendingId={}, agentJobId={}",
                userId, tagId, submission.pendingId(), submission.agentJobId());
        return GenerationTriggerResponse.accepted(submission.pendingId(), submission.agentJobId());
    }

    private List<WikiTag> currentTags(long userId) {
        WikiTagsResponse response = wikiClient.getTags(userId);
        return response.tags() == null ? List.of() : response.tags();
    }

    /** 같은 분의 연타만 합치고 리포트 종류·Wiki 관심사는 서로 다른 Job으로 유지한다. */
    static String minuteKey(String kind, long userId, String discriminator) {
        long minute = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).toEpochSecond();
        String prefix = kind + "-" + userId;
        return discriminator == null
                ? prefix + "-" + minute
                : prefix + "-" + discriminator + "-" + minute;
    }
}
