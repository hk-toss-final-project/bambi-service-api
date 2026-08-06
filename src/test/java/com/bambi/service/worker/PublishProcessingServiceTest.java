package com.bambi.service.worker;

import com.bambi.service.agent.publish.dto.PublishItem;
import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.notification.NotificationService;
import com.bambi.service.report.Report;
import com.bambi.service.report.ReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublishProcessingService} — content_id+version 진짜 upsert + 리포트(본문)+카드 동시 저장.
 * 신규 저장 / 더 큰 version 갱신 / 같거나 작은 version skip.
 */
class PublishProcessingServiceTest {

    private final CardRepository cardRepository = mock(CardRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PublishProcessingService service =
            new PublishProcessingService(cardRepository, reportRepository, notificationService);

    private static PublishItem item(String contentId, int version, String title, String summary) {
        // content_tags·report_type 미도착(단계적 롤아웃 전) → tags(topic) 폴백 + reportType null 경로
        return new PublishItem(contentId, "1", version, "hash-" + version, title, summary, "본문-" + version,
                List.of(new PublishItem.Citation("src", "https://example.com")),
                List.of("코스피"), null, null);
    }

    @Test
    void 없으면_리포트와_카드를_신규_저장한다() {
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean ok = service.upsert(item("c1", 1, "제목", "요약"));

        assertThat(ok).isTrue();
        ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getBody()).isEqualTo("본문-1");   // body 는 리포트에 보존
        assertThat(reportCaptor.getValue().getCitations()).hasSize(1);
        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());                   // 카드(요약)도 저장
        assertThat(cardCaptor.getValue().getInterestTags()).containsExactly("코스피");   // 발행 태그 저장
        // report_type 미도착 스냅샷 → 카드·리포트·알림 모두 null (관용 파싱, 기존 계약 그대로)
        assertThat(reportCaptor.getValue().getReportType()).isNull();
        assertThat(cardCaptor.getValue().getReportType()).isNull();
        verify(notificationService).notifyReportReady(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("제목"),
                org.mockito.ArgumentMatchers.eq("요약"),
                any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void report_type이_오면_리포트와_카드에_저장하고_알림에도_싣는다() {
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        PublishItem item = new PublishItem("c1", "1", 1, "hash-1", "제목", "요약", "본문",
                List.of(), List.of(), List.of("반도체"), "ON_DEMAND");

        service.upsert(item);

        ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getReportType()).isEqualTo("ON_DEMAND");
        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().getReportType()).isEqualTo("ON_DEMAND");   // 조인 없이 서빙용 복제
        verify(notificationService).notifyReportReady(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("제목"),
                org.mockito.ArgumentMatchers.eq("요약"),
                any(),
                org.mockito.ArgumentMatchers.eq("ON_DEMAND"));
    }

    @Test
    void report_type_없는_재발행은_이미_저장된_유형을_지우지_않는다() {
        Card existingCard = Card.fromExternal(1L, "c1", 1, "제목", "요약", null);
        existingCard.applyReportType("MORNING_BRIEFING");
        Report existingReport = Report.fromExternal(1L, "c1", 1, "제목", "요약", "본문");
        existingReport.applyReportType("MORNING_BRIEFING");
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existingCard));
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existingReport));

        service.upsert(item("c1", 2, "새 제목", "새 요약"));   // report_type null 재발행(구버전 agent·롤백)

        assertThat(existingCard.getReportType()).isEqualTo("MORNING_BRIEFING");
        assertThat(existingReport.getReportType()).isEqualTo("MORNING_BRIEFING");
    }

    @Test
    void content_tags가_오면_topic_대신_실제_태그를_저장한다() {
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        // tags=topic 에코, content_tags=리포트 내용 기반 실제 태그
        PublishItem item = new PublishItem("c1", "1", 1, "hash-1", "제목", "요약", "본문",
                List.of(), List.of("오늘의 관심사 뉴스"), List.of("군사 AI", "AI 규제"), null);

        service.upsert(item);

        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().getInterestTags())
                .containsExactly("군사 AI", "AI 규제");   // topic 에코가 아니라 content_tags
    }

    @Test
    void 더_큰_version_이면_리포트_본문과_카드를_갱신한다() {
        Card existingCard = Card.fromExternal(1L, "c1", 1, "옛 제목", "옛 요약", null);
        existingCard.addSource("old", "https://old");
        existingCard.replaceInterestTags(List.of("옛 태그"));
        Report existingReport = Report.fromExternal(1L, "c1", 1, "옛 제목", "옛 요약", "옛 본문");
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existingCard));
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existingReport));

        boolean ok = service.upsert(item("c1", 2, "새 제목", "새 요약"));

        assertThat(ok).isTrue();
        // 카드 갱신
        assertThat(existingCard.getExternalVersion()).isEqualTo(2);
        assertThat(existingCard.getTitle()).isEqualTo("새 제목");
        assertThat(existingCard.getSources()).hasSize(1);   // 통째 교체
        // 리포트 본문 갱신
        assertThat(existingReport.getExternalVersion()).isEqualTo(2);
        assertThat(existingReport.getBody()).isEqualTo("본문-2");
        assertThat(existingReport.getCitations()).hasSize(1);
        assertThat(existingCard.getInterestTags()).containsExactly("코스피");   // 태그 통째 교체(옛 태그 제거)
        // 갱신은 dirty checking — 새 row insert(save) 아님
        verify(cardRepository, never()).save(any(Card.class));
        verify(reportRepository, never()).save(any(Report.class));
        verify(notificationService, never()).notifyReportReady(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 같은_version_이면_전체_skip_한다() {
        Card existingCard = Card.fromExternal(1L, "c1", 2, "제목", "요약", null);
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existingCard));

        boolean ok = service.upsert(item("c1", 2, "덮어쓰기 시도", "덮어쓰기"));

        assertThat(ok).isTrue();
        assertThat(existingCard.getTitle()).isEqualTo("제목");   // 안 바뀜
        verify(cardRepository, never()).save(any(Card.class));
        verify(reportRepository, never()).save(any(Report.class));
    }

    @Test
    void 더_작은_version_이면_전체_skip_한다() {
        Card existingCard = Card.fromExternal(1L, "c1", 5, "최신", "최신요약", null);
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existingCard));

        boolean ok = service.upsert(item("c1", 3, "구버전", "구버전요약"));

        assertThat(ok).isTrue();
        assertThat(existingCard.getTitle()).isEqualTo("최신");
        assertThat(existingCard.getExternalVersion()).isEqualTo(5);
        verify(cardRepository, never()).save(any(Card.class));
        verify(reportRepository, never()).save(any(Report.class));
    }
}
