package com.bambi.service.report;

import com.bambi.service.report.dto.ReportResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 리포트 상세 응답의 대표 이미지·본문 폼 신호 계약을 검증한다. */
class ReportResponseTest {

    @Test
    void 본문_폼_신호를_상세_응답에_포함한다() {
        Report report = Report.fromExternal(1L, "content-1", 1, "제목", "요약", "## 이번에 달라진 점");
        report.applyChangeHistoryEnabled(true);

        assertThat(ReportResponse.from(report).changeHistoryEnabled()).isTrue();
    }

    @Test
    void 폼_신호가_없는_리포트는_false로_내려간다() {
        // 필드 도입 전 리포트 — 프론트는 이 값을 "기존 자유 형식"으로 읽는다.
        Report report = Report.fromExternal(1L, "content-1", 1, "제목", "요약", "본문");

        assertThat(ReportResponse.from(report).changeHistoryEnabled()).isFalse();
    }

    @Test
    void 대표_이미지와_원문_출처를_상세_응답에_포함한다() {
        Report report = Report.fromExternal(1L, "content-1", 1, "제목", "요약", "본문");
        report.applyCoverImage(
                "https://cdn.example.com/cover.jpg",
                "https://news.example.com/article",
                "기사 제목",
                "G1");

        ReportResponse response = ReportResponse.from(report);

        assertThat(response.coverImage()).isNotNull();
        assertThat(response.coverImage().url()).isEqualTo("https://cdn.example.com/cover.jpg");
        assertThat(response.coverImage().sourceUrl()).isEqualTo("https://news.example.com/article");
        assertThat(response.coverImage().sourceTitle()).isEqualTo("기사 제목");
        assertThat(response.coverImage().reference()).isEqualTo("G1");
    }
}
