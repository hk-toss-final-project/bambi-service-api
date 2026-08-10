package com.bambi.service.worker;

import com.bambi.service.agent.publish.dto.PublishItem;
import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.generation.GenerationPendingService;
import com.bambi.service.notification.NotificationService;
import com.bambi.service.report.Report;
import com.bambi.service.report.ReportRepository;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GenerationPendingService pendingService = mock(GenerationPendingService.class);
    private final PublishProcessingService service =
            new PublishProcessingService(
                    cardRepository, reportRepository, notificationService, userRepository, pendingService);

    private static PublishItem item(String contentId, int version, String title, String summary) {
        // content_tags·report_type 미도착(단계적 롤아웃 전) → tags(topic) 폴백 + reportType null 경로
        return new PublishItem(contentId, "1", version, "hash-" + version, title, summary, "본문-" + version,
                List.of(new PublishItem.Citation("src", "https://example.com")),
                List.of("코스피"), null, null, null, null, null);
    }

    /** 설정 적용 테스트용 사용자 목 — 기본 공개범위 + 알림 수신 여부. */
    private static User userWith(String defaultVisibility, boolean reportReadyNotification) {
        User user = mock(User.class);
        when(user.getDefaultCardVisibility()).thenReturn(defaultVisibility);
        when(user.isReportReadyNotification()).thenReturn(reportReadyNotification);
        return user;
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
                List.of(), List.of(), List.of("반도체"), "ON_DEMAND", null, null, null);

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
                List.of(), List.of("오늘의 관심사 뉴스"), List.of("군사 AI", "AI 규제"), null, null, null, null);

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

    // ── 사용자 설정(V17) 적용 ──────────────────────────────────

    @Test
    void 신규_발행카드는_사용자_기본공개범위를_따른다_PUBLIC() {
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        User user = userWith("PUBLIC", true);   // 중첩 스터빙(UnfinishedStubbing) 회피 — 먼저 만든다
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.upsert(item("c1", 1, "제목", "요약"));

        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().getVisibility()).isEqualTo("PUBLIC");   // 하드코딩 PRIVATE 이 아니라 설정값
        verify(notificationService).notifyReportReady(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 알림수신_OFF_면_REPORT_READY_알림을_만들지_않는다_카드는_저장() {
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        User user = userWith("PRIVATE", false);   // 중첩 스터빙 회피 — 먼저 만든다
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.upsert(item("c1", 1, "제목", "요약"));

        verify(cardRepository).save(any(Card.class));   // 발행 자체는 진행(카드 저장)
        verify(notificationService, never()).notifyReportReady(   // 알림만 "생성 안 함"
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 갱신_카드의_공개범위는_설정을_다시_적용하지_않는다() {
        // 사용자가 PUBLIC 으로 토글해둔 카드를 더 큰 version 으로 갱신 → 공개범위 유지, 설정 재조회조차 안 함
        Card existing = Card.fromExternal(1L, "c1", 1, "옛 제목", "옛 요약", null);
        existing.changeVisibility("PUBLIC");
        Report existingReport = Report.fromExternal(1L, "c1", 1, "옛 제목", "옛 요약", "옛 본문");
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existing));
        when(reportRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.of(existingReport));

        service.upsert(item("c1", 2, "새 제목", "새 요약"));

        assertThat(existing.getVisibility()).isEqualTo("PUBLIC");   // 갱신은 공개범위 안 건드림
        verify(userRepository, never()).findById(any());           // 갱신 경로는 설정 조회조차 안 함
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
    void 발행_반영후_요청_멱등키로_생성작업을_완료한다() {
        Card existingCard = Card.fromExternal(1L, "c1", 1, "제목", "요약", null);
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1"))
                .thenReturn(Optional.of(existingCard));
        PublishItem published = new PublishItem(
                "c1", "1", 1, "hash-1", "제목", "요약", "본문",
                List.of(), List.of(), List.of(), "ON_DEMAND", "generation-key-1", null, null);

        service.upsert(published);

        // 신규 카드가 아니므로 newCard=false — 연결 키가 있을 때만 닫힌다(근사 매칭 금지, 우석 가드 2).
        verify(pendingService).completeFromSnapshot(
                eq(1L), eq(published), eq(existingCard.getPublicId()), eq(false));
    }

    @Test
    void 신규_발행이면_완성_카드를_함께_넘긴다() {
        // 프론트가 "처리중" 슬롯을 완성 카드로 바꿔 끼우려면 어느 카드가 됐는지가 필요하다.
        when(cardRepository.findByUserIdAndExternalContentId(1L, "c1")).thenReturn(Optional.empty());
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        PublishItem published = new PublishItem(
                "c1", "1", 1, "hash-1", "제목", "요약", "본문",
                List.of(), List.of(), List.of(), "ON_DEMAND", "generation-key-1", null, null);

        service.upsert(published);

        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());
        verify(pendingService).completeFromSnapshot(
                eq(1L), eq(published), eq(cardCaptor.getValue().getPublicId()), eq(true));
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
