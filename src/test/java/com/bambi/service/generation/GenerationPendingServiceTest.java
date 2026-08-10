package com.bambi.service.generation;

import com.bambi.service.generation.dto.GenerationPendingResponse;
import com.bambi.service.agent.jobs.AgentJobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GenerationPendingService} — 결정적 id·Agent 상태 전이·최근 작업 조회.
 */
class GenerationPendingServiceTest {

    private final GenerationPendingRepository repository = mock(GenerationPendingRepository.class);
    private final GenerationPendingService service = new GenerationPendingService(repository);

    @Test
    @DisplayName("같은 멱등키는 항상 같은 id 로 파생된다 (트리거 응답과 펜딩 행 매칭의 근거)")
    void deterministicIdIsStable() {
        UUID first = GenerationPendingService.deterministicId("ondemand-28-interest_news_card-1000");
        UUID second = GenerationPendingService.deterministicId("ondemand-28-interest_news_card-1000");
        UUID other = GenerationPendingService.deterministicId("ondemand-28-interest_news_card-1060");

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    @DisplayName("register 는 멱등 insert 후 파생 id 를 반환한다")
    void registerInsertsAndReturnsId() {
        String id = service.register(28L, "key-1", GenerationPendingService.REPORT_TYPE_ON_DEMAND,
                "SK하이닉스", "interest_news_card", "job-1");

        assertThat(id).isEqualTo(GenerationPendingService.deterministicId("key-1").toString());
        verify(repository).insertPending(
                eq(GenerationPendingService.deterministicId("key-1")),
                eq(28L), eq("key-1"),
                eq(GenerationPendingService.REPORT_TYPE_ON_DEMAND),
                eq("SK하이닉스"), eq("interest_news_card"), eq("job-1"));
    }

    @Test
    @DisplayName("기록 실패는 삼키고 id 는 그대로 반환한다 (agent 접수는 이미 성공)")
    void registerSwallowsInsertFailure() {
        doThrow(new RuntimeException("db down"))
                .when(repository).insertPending(any(), any(), any(), any(), any(), any(), any());

        String id = service.register(28L, "key-1", GenerationPendingService.REPORT_TYPE_MORNING_BRIEFING,
                "반도체", "interest_news_card", null);

        assertThat(id).isNotBlank();
    }

    @Test
    @DisplayName("listRecent 는 본인 활성 작업을 조회해 응답으로 매핑한다")
    void listRecentMapsEntities() {
        GenerationPending pending = mock(GenerationPending.class);
        UUID id = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.now().minusMinutes(5);
        when(pending.getId()).thenReturn(id);
        when(pending.getTopic()).thenReturn("SK하이닉스");
        when(pending.getContentType()).thenReturn("interest_news_card");
        when(pending.getReportType()).thenReturn(GenerationPendingService.REPORT_TYPE_ON_DEMAND);
        when(pending.getStatus()).thenReturn("PENDING");
        when(pending.getCreatedAt()).thenReturn(created);
        when(pending.getUpdatedAt()).thenReturn(created);
        when(repository.findByUserIdAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(28L), eq(List.of("PENDING", "RUNNING", "PUBLISHING")), any()))
                .thenReturn(List.of(pending));

        List<GenerationPendingResponse> result = service.listRecent(28L);

        assertThat(result).hasSize(1);
        GenerationPendingResponse response = result.get(0);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.topic()).isEqualTo("SK하이닉스");
        assertThat(response.reportType()).isEqualTo("ON_DEMAND");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.createdAt()).isEqualTo(created);
        assertThat(response.updatedAt()).isEqualTo(created);
    }

    @Test
    @DisplayName("Agent Job 완료는 Publish 반영 전 PUBLISHING으로 전환한다")
    void completedAgentJobWaitsForPublish() {
        GenerationPending pending = mock(GenerationPending.class);
        UUID id = UUID.randomUUID();
        when(pending.getId()).thenReturn(id);

        service.applyAgentStatus(pending,
                new AgentJobStatus("job-1", "report_generation", "completed", 100, null));

        verify(repository).updateStatus(id, "PUBLISHING", null);
    }

    @Test
    @DisplayName("Publish idempotency key로 생성 작업을 완료한다")
    void publishMarksPendingCompleted() {
        service.markCompleted(28L, "generation-key-1");

        verify(repository).markCompleted(28L, "generation-key-1", null);
    }
}
