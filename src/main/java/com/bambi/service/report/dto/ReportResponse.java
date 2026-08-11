package com.bambi.service.report.dto;

import com.bambi.service.report.Report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 리포트(본문) 상세 응답 — 카드 상세 화면의 본문/인용 노출용.
 * 대외 식별자는 publicId(UUID). 카드 요약과 달리 body(본문)를 포함한다.
 * reportType = 생성 유형(MORNING_BRIEFING|ON_DEMAND, nullable) — 기존 생성분·롤아웃 전 발행은 null.
 *
 * <p>changeHistoryEnabled = 이 body 를 어떤 규칙으로 렌더할지(2026-08-11 김기용 계약).
 * true 면 "이번에 달라진 점 / 보고서 내용 / 주목할 점 / 타임라인" 4단 폼이고, false 면
 * 지금까지와 같은 자유 형식이다. 프론트는 이 값으로만 분기하고 본문 헤더 문자열을 파싱해
 * 폼을 추측하지 않는다. 필드가 없던 과거 리포트는 전부 false 다.
 * ⚠️ 계정 설정(GET /api/auth/me 의 changeHistoryEnabled)과 다른 값이다 — 저쪽은 "앞으로
 * 생성할 때"이고 이쪽은 "이 본문이 무엇인지"다. 설정으로 렌더링을 판단하면 사용자가 설정을
 * 끈 순간 과거 델타 리포트가 전부 깨진다.
 */
public record ReportResponse(
        UUID publicId,
        String title,
        String summary,
        String body,
        String reportType,
        ReportCoverImageResponse coverImage,
        boolean changeHistoryEnabled,
        List<CitationResponse> citations,
        OffsetDateTime createdAt) {

    public record CitationResponse(String title, String url) {
    }

    public static ReportResponse from(Report report) {
        List<CitationResponse> citations = report.getCitations().stream()
                .map(c -> new CitationResponse(c.getTitle(), c.getUrl()))
                .toList();
        return new ReportResponse(
                report.getPublicId(),
                report.getTitle(),
                report.getSummary(),
                report.getBody(),
                report.getReportType(),
                ReportCoverImageResponse.from(report),
                report.isChangeHistoryEnabled(),
                citations,
                report.getCreatedAt());
    }
}
