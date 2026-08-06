package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentContextRequest;
import com.bambi.service.interest.Interest;
import com.bambi.service.interest.InterestRepository;
import com.bambi.service.interest.InterestSource;
import com.bambi.service.interest.taxonomy.InterestTaxonomyService;
import com.bambi.service.interest.taxonomy.dto.InterestTaxonomyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** taxonomy Snapshot과 사용자 선택을 Agent Context 계약으로 조립하는지 검증한다. */
class AgentContextSyncServiceTest {

    private final InterestRepository interestRepository = mock(InterestRepository.class);
    private final InterestTaxonomyService taxonomyService = mock(InterestTaxonomyService.class);
    private final AgentContextVersionAllocator versionAllocator = mock(AgentContextVersionAllocator.class);
    private final AgentGateway agentGateway = mock(AgentGateway.class);
    private final AgentContextSyncService service = new AgentContextSyncService(
            interestRepository, taxonomyService, versionAllocator, agentGateway);

    @BeforeEach
    void setUp() {
        when(taxonomyService.getActiveTaxonomy()).thenReturn(taxonomy());
        when(versionAllocator.allocate(1L)).thenReturn(1);
    }

    @Test
    void 활성_taxonomy를_먼저_동기화하고_할당된_버전으로_context를_보낸다() {
        service.syncUserContext(1L);

        verify(agentGateway).syncInterestTaxonomy(any());
        ArgumentCaptor<AgentContextRequest> captor = ArgumentCaptor.forClass(AgentContextRequest.class);
        verify(agentGateway).syncUserContext(anyLong(), captor.capture());
        assertThat(captor.getValue().contextVersion()).isEqualTo(1);
        assertThat(captor.getValue().signupInterests()).isEmpty();
    }

    @Test
    void taxonomy_선택과_직접입력_topic을_구분해_스냅샷에_싣는다() {
        Interest taxonomyInterest = Interest.fromTaxonomy(
                1L, "AI·머신러닝", "1.0.0-draft", "tech", "ai_ml");
        Interest customInterest = new Interest(1L, "양자 센서 스타트업");
        when(interestRepository.findByUserIdAndSourceAndDeletedAtIsNullOrderByNameAsc(
                1L, InterestSource.USER)).thenReturn(List.of(taxonomyInterest, customInterest));

        service.syncUserContext(1L);

        ArgumentCaptor<AgentContextRequest> captor = ArgumentCaptor.forClass(AgentContextRequest.class);
        verify(agentGateway).syncUserContext(anyLong(), captor.capture());
        AgentContextRequest request = captor.getValue();
        assertThat(request.interestTaxonomyVersion()).isEqualTo("1.0.0-draft");
        assertThat(request.selectedCategoryIds()).containsExactly("tech");
        assertThat(request.selectedTopicIds()).containsExactly("ai_ml");
        assertThat(request.signupInterests()).hasSize(2);
        assertThat(request.signupInterests().get(0).category()).isEqualTo("테크·IT");
        assertThat(request.signupInterests().get(0).topics()).containsExactly("AI·머신러닝");
        assertThat(request.signupInterests().get(1).category()).isNull();
        assertThat(request.signupInterests().get(1).topics()).containsExactly("양자 센서 스타트업");
    }

    @Test
    void STALE_신호를_받으면_agent_현재버전에_맞춰_한번_재전송한다() {
        when(versionAllocator.reconcile(1L, 7)).thenReturn(8);
        // 첫 전송은 STALE(agent 현재 7), 재전송은 성공
        doThrow(new StaleContextVersionException(7))
                .doNothing()
                .when(agentGateway).syncUserContext(anyLong(), any());

        service.syncUserContext(1L);

        verify(versionAllocator).reconcile(1L, 7);
        ArgumentCaptor<AgentContextRequest> captor = ArgumentCaptor.forClass(AgentContextRequest.class);
        verify(agentGateway, times(2)).syncUserContext(anyLong(), captor.capture());
        // 재전송은 정합된 버전(8)으로 나가야 한다
        assertThat(captor.getAllValues().get(1).contextVersion()).isEqualTo(8);
    }

    @Test
    void 재전송도_STALE이면_500으로_터뜨리지_않고_다음_동기화에_위임한다() {
        when(versionAllocator.reconcile(1L, 7)).thenReturn(8);
        // 첫 전송·재전송 둘 다 STALE(재경합) — 예외가 밖으로 새면 sync 엔드포인트가 500 이 된다
        doThrow(new StaleContextVersionException(7))
                .doThrow(new StaleContextVersionException(9))
                .when(agentGateway).syncUserContext(anyLong(), any());

        assertThatCode(() -> service.syncUserContext(1L)).doesNotThrowAnyException();

        verify(agentGateway, times(2)).syncUserContext(anyLong(), any());
    }

    @Test
    void 버전_할당_실패시_context를_호출하지_않는다() {
        when(versionAllocator.allocate(99L)).thenThrow(new IllegalStateException("사용자 없음"));

        assertThatThrownBy(() -> service.syncUserContext(99L))
                .isInstanceOf(IllegalStateException.class);

        verify(agentGateway, never()).syncUserContext(anyLong(), any());
    }

    private static InterestTaxonomyResponse taxonomy() {
        return new InterestTaxonomyResponse(
                "1.0.0-draft",
                "a".repeat(64),
                "ko-KR",
                OffsetDateTime.now(),
                List.of(new InterestTaxonomyResponse.Category(
                        "tech",
                        "테크·IT",
                        "Tech & IT",
                        "기술",
                        "💻",
                        1,
                        List.of(new InterestTaxonomyResponse.Topic(
                                "ai_ml",
                                "AI·머신러닝",
                                "AI & Machine Learning",
                                "AI",
                                1,
                                List.of("LLM"))))));
    }
}
