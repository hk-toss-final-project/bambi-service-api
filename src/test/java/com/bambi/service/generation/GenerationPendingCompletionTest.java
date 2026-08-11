package com.bambi.service.generation;

import com.bambi.service.agent.publish.dto.PublishItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 발행 Snapshot → 생성 작업 완료 전환 (2026-08-10).
 *
 * <p>연결 키({@code request_idempotency_key})가 오면 그것으로 정확히 닫고, <b>2026-08-10 이전에
 * 이미 생성돼 저장된 Snapshot</b>(이 값이 빈 문자열, 소급 불가 — 김기용 확인)만 근사 매칭으로 덮는다.
 *
 * <p>여기서 고정하는 건 우석님이 리뷰에서 짚은 가드 3개다.
 * <ol>
 *   <li>근사 매칭은 <b>신규 카드에만</b> — 재발행이 나중에 접수된 펜딩을 오완료하면 안 된다</li>
 *   <li>{@code snapshot.created_at} 이후 접수는 제외 — 같은 이유</li>
 *   <li>매칭 실패는 <b>오류가 아니다</b> — agent 자체 생성 경로에는 이을 펜딩이 애초에 없다</li>
 * </ol>
 */
class GenerationPendingCompletionTest {

    private static final OffsetDateTime SNAPSHOT_AT = OffsetDateTime.parse("2026-08-10T07:03:00+09:00");

    private final GenerationPendingRepository repository = mock(GenerationPendingRepository.class);
    private final GenerationPendingService service = new GenerationPendingService(repository);

    // ---- 정공법: 연결 키가 있을 때 -------------------------------------------------

    @Test
    @DisplayName("연결 키가 오면 그것으로 정확히 닫고 카드도 잇는다")
    void exactKeyClosesPending() {
        UUID cardPublicId = UUID.randomUUID();
        when(repository.markCompleted(anyLong(), anyString(), any())).thenReturn(1);

        service.completeFromSnapshot(1L, item("key-1", "ON_DEMAND", "폭염", SNAPSHOT_AT), cardPublicId, true);

        verify(repository).markCompleted(1L, "key-1", cardPublicId);
        verify(repository, never()).findApproximateMatch(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("연결 키가 있으면 갱신(재발행)에도 정확히 닫는다 — 같은 요청이 맞기 때문")
    void exactKeyWorksOnRepublish() {
        when(repository.markCompleted(anyLong(), anyString(), any())).thenReturn(1);

        service.completeFromSnapshot(1L, item("key-1", "ON_DEMAND", "폭염", SNAPSHOT_AT), UUID.randomUUID(), false);

        verify(repository).markCompleted(eq(1L), eq("key-1"), any());
    }

    @Test
    @DisplayName("이미 닫힌 행이어도 카드 링크는 채워 준다")
    void linksCardWhenAlreadyCompleted() {
        UUID cardPublicId = UUID.randomUUID();
        when(repository.markCompleted(anyLong(), anyString(), any())).thenReturn(0);   // 이미 COMPLETED

        service.completeFromSnapshot(1L, item("key-1", "ON_DEMAND", "폭염", SNAPSHOT_AT), cardPublicId, true);

        verify(repository).linkCard(1L, "key-1", cardPublicId);
    }

    // ---- 우석 가드 1·2: 근사 매칭 범위 --------------------------------------------

    @Test
    @DisplayName("가드1 — 연결 키가 없으면 신규 카드에만 근사 매칭한다(재발행은 건너뜀)")
    void approximateMatchOnlyOnNewCard() {
        service.completeFromSnapshot(1L, item("", "ON_DEMAND", "폭염", SNAPSHOT_AT), UUID.randomUUID(), false);

        // 기존 카드 v2 재발행이 나중에 접수된 다른 펜딩을 잘못 닫는 것을 막는다.
        verify(repository, never()).findApproximateMatch(anyLong(), anyString(), any(), any());
        verify(repository, never()).markCompletedById(any(), any());
    }

    @Test
    @DisplayName("가드2 — 근사 매칭은 snapshot 발행 시각 이전에 접수된 것만 본다")
    void approximateMatchBoundedBySnapshotTime() {
        UUID pendingId = UUID.randomUUID();
        UUID cardPublicId = UUID.randomUUID();
        // 목 생성은 바깥 when(...) 인자 안에서 하면 안 된다 — 중첩 스텁으로 잡힌다.
        GenerationPending matched = pendingWithId(pendingId);
        when(repository.findApproximateMatch(1L, "ON_DEMAND", "폭염", SNAPSHOT_AT))
                .thenReturn(Optional.of(matched));

        service.completeFromSnapshot(1L, item("", "ON_DEMAND", "폭염", SNAPSHOT_AT), cardPublicId, true);

        // 발행 시각을 그대로 상한으로 넘긴다 — 이 카드보다 나중에 접수된 펜딩은 쿼리에서 빠진다.
        verify(repository).findApproximateMatch(1L, "ON_DEMAND", "폭염", SNAPSHOT_AT);
        verify(repository).markCompletedById(pendingId, cardPublicId);
    }

    @Test
    @DisplayName("아침 브리핑은 주제를 안 보고 매칭한다 — 요청 topic 이 고정 문구라 펜딩과 다르다")
    void morningBriefingMatchesWithoutTopic() {
        when(repository.findApproximateMatch(anyLong(), anyString(), isNull(), any()))
                .thenReturn(Optional.empty());

        service.completeFromSnapshot(
                1L, item("", "MORNING_BRIEFING", "오늘의 관심사 브리핑", SNAPSHOT_AT), UUID.randomUUID(), true);

        // 멱등키가 {날짜}-{userId}-{contentType} 라 그날 1건뿐이므로 주제 없이도 유일하다.
        verify(repository).findApproximateMatch(1L, "MORNING_BRIEFING", null, SNAPSHOT_AT);
    }

    // ---- 기용 확인 사항: 매칭 실패는 오류가 아니다 ---------------------------------

    @Test
    @DisplayName("가드3 — 매칭되는 접수가 없어도 조용히 넘어간다(agent 자체 생성 경로)")
    void noMatchIsNotAnError() {
        when(repository.findApproximateMatch(anyLong(), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        // agent 내부 멱등키(interest-report:...)가 실려도 service 가 보낸 요청이 아니라 이을 펜딩이 없다.
        service.completeFromSnapshot(1L, item("", "ON_DEMAND", "폭염", SNAPSHOT_AT), UUID.randomUUID(), true);

        verify(repository, never()).markCompletedById(any(), any());
    }

    @Test
    @DisplayName("발행 시각이 없으면 근사 매칭을 아예 시도하지 않는다")
    void skipsWhenSnapshotTimeMissing() {
        service.completeFromSnapshot(1L, item("", "ON_DEMAND", "폭염", null), UUID.randomUUID(), true);

        // 상한을 못 거는 매칭은 나중에 접수된 펜딩까지 닫을 수 있다 — 잘못 닫느니 열어 둔다.
        verify(repository, never()).findApproximateMatch(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("생성 유형이 없으면 근사 매칭을 아예 시도하지 않는다")
    void skipsWhenReportTypeMissing() {
        service.completeFromSnapshot(1L, item("", null, "폭염", SNAPSHOT_AT), UUID.randomUUID(), true);

        verify(repository, never()).findApproximateMatch(anyLong(), any(), any(), any());
    }

    private static PublishItem item(String requestKey, String reportType, String generationTopic,
                                    OffsetDateTime createdAt) {
        return new PublishItem("c1", "1", 1, "hash-1", "제목", "요약", "본문",
                List.of(), List.of(), List.of(), reportType, requestKey, generationTopic, createdAt, null, null);
    }

    private static GenerationPending pendingWithId(UUID id) {
        GenerationPending pending = mock(GenerationPending.class);
        when(pending.getId()).thenReturn(id);
        return pending;
    }
}
