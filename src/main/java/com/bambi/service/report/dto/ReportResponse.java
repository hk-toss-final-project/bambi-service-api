package com.bambi.service.report.dto;

import com.bambi.service.report.Report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 리포트(본문) 상세 응답 — 카드 상세 화면의 본문/인용 노출용.
 * 대외 식별자는 publicId(UUID). 카드 요약과 달리 body(본문)를 포함한다.
 * reportType = 생성 유형(MORNING_BRIEFING|ON_DEMAND, nullable) — 기존 생성분·롤아웃 전 발행은 null.
 */
public record ReportResponse(
        UUID publicId,
        String title,
        String summary,
        String body,
        String reportType,
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
                citations,
                report.getCreatedAt());
    }
}
