package com.bambi.service.generation;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.generation.dto.GenerationRequest;
import com.bambi.service.generation.dto.GenerationTriggerResponse;
import com.bambi.service.wiki.AgentWikiClient;
import com.bambi.service.wiki.dto.WikiTag;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OnDemandGenerationService} — 대표 관심사(score 최고)를 검색 주제로 즉시 생성.
 * 관심사 있으면 id(항상)+agentJobId(참고) 반환, 없으면 VALIDATION_ERROR.
 * topic 은 표시 라벨이 아니라 실제 검색어라 대표 관심사를 넣는다(우석/유림 08-05).
 */
class OnDemandGenerationServiceTest {

    private final GenerationClient generationClient = mock(GenerationClient.class);
    private final AgentWikiClient wikiClient = mock(AgentWikiClient.class);
    // 펜딩 접수 레이어 — id 파생 로직이 응답 id 와 결합돼 있어 실제 구현 + repo mock 으로 검증한다.
    private final GenerationPendingRepository pendingRepository = mock(GenerationPendingRepository.class);
    private final GenerationPendingService pendingService = new GenerationPendingService(pendingRepository);
    private final com.bambi.service.interest.InterestService interestService =
            mock(com.bambi.service.interest.InterestService.class);
    private final OnDemandGenerationService service = new OnDemandGenerationService(
            generationClient, wikiClient, pendingService, interestService, "interest_news_card");

    /** 앞쪽 태그일수록 score 를 높게 → 대표 관심사 = names[0]. */
    private static WikiTagsResponse tagsWith(String... names) {
        List<WikiTag> tags = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            tags.add(new WikiTag("id-" + names[i], names[i], "organization",
                    1.0 - i * 0.1, 0.7, List.of(), java.util.Map.of()));
        }
        return new WikiTagsResponse("prof", 1, "active", null, tags);
    }

    @Test
    @DisplayName("대표 관심사(score 최고)를 검색 주제로 접수하고 id(멱등키 파생)+agentJobId 를 반환한다")
    void triggersWithTopInterestReturnsIds() {
        when(wikiClient.getTags(28L)).thenReturn(tagsWith("SK하이닉스", "삼성전자"));
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn("job-99");

        GenerationTriggerResponse response = service.generateForUser(28L, null);

        assertThat(response.status()).isEqualTo("accepted");
        assertThat(response.agentJobId()).isEqualTo("job-99");   // agent 식별자(참고용)
        ArgumentCaptor<GenerationRequest> captor = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(generationClient).requestGeneration(eq(28L), captor.capture());
        // 고정 문구가 아니라 대표 관심사가 실제 검색 주제로 들어가야 한다(엉뚱한 기사 방지 — 우석/유림 08-05).
        assertThat(captor.getValue().topic()).isEqualTo("SK하이닉스");
        assertThat(captor.getValue().idempotencyKey()).startsWith("ondemand-28-interest_news_card-");
        // id 는 항상 보장되고 멱등키에서 결정적으로 파생된다(같은 분 연타 = 같은 id → 펜딩 중복 방지).
        String expectedId = java.util.UUID.nameUUIDFromBytes(
                captor.getValue().idempotencyKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertThat(response.id()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("agent 식별자가 null(202 body 파싱 실패)이어도 id 는 보장되고 접수로 응답한다")
    void agentJobIdNullStillReturnsId() {
        when(wikiClient.getTags(28L)).thenReturn(tagsWith("SK하이닉스"));
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn(null);   // 접수는 성공, 식별자만 못 읽음

        GenerationTriggerResponse response = service.generateForUser(28L, null);

        assertThat(response.status()).isEqualTo("accepted");
        assertThat(response.agentJobId()).isNull();     // 참고용 — null 가능
        assertThat(response.id()).isNotBlank();         // 펜딩 키는 항상 보장
    }

    @Test
    @DisplayName("대표 관심사는 순서가 아니라 score 최고 태그로 고른다")
    void picksHighestScoreNotFirst() {
        WikiTagsResponse interests = new WikiTagsResponse("prof", 1, "active", null, List.of(
                new WikiTag("id-a", "부동산", "topic", 0.3, 0.7, List.of(), java.util.Map.of()),
                new WikiTag("id-b", "반도체", "topic", 0.9, 0.7, List.of(), java.util.Map.of())));
        when(wikiClient.getTags(28L)).thenReturn(interests);
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn("job-1");

        service.generateForUser(28L, null);

        ArgumentCaptor<GenerationRequest> captor = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(generationClient).requestGeneration(eq(28L), captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("반도체");   // score 0.9 > 0.3
    }

    @Test
    @DisplayName("관심사가 없으면 VALIDATION_ERROR 로 막고 생성하지 않는다")
    void noInterestRejects() {
        when(wikiClient.getTags(28L)).thenReturn(WikiTagsResponse.empty());

        assertThatThrownBy(() -> service.generateForUser(28L, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        verify(generationClient, never()).requestGeneration(any(Long.class), any());
        // 접수가 안 됐으니 펜딩도 안 남는다
        verify(pendingRepository, never()).insertPending(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("접수 성공 시 펜딩을 ON_DEMAND 로 기록한다 (topic·agentJobId 포함)")
    void registersPendingAsOnDemand() {
        when(wikiClient.getTags(28L)).thenReturn(tagsWith("SK하이닉스"));
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn("job-99");

        GenerationTriggerResponse response = service.generateForUser(28L, null);

        // 펜딩 행의 id = 응답 id (멱등키 파생 결정적 UUID — 프론트가 접수 응답과 목록을 매칭)
        verify(pendingRepository).insertPending(
                eq(java.util.UUID.fromString(response.id())),
                eq(28L),
                any(),
                eq(GenerationPendingService.REPORT_TYPE_ON_DEMAND),
                eq("SK하이닉스"),
                eq("interest_news_card"),
                eq("job-99"));
    }

    @Test
    @DisplayName("펜딩 기록이 실패해도 접수 응답은 정상 반환된다 (기록 실패는 삼킴)")
    void pendingFailureDoesNotBlockResponse() {
        when(wikiClient.getTags(28L)).thenReturn(tagsWith("SK하이닉스"));
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn("job-99");
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(pendingRepository).insertPending(any(), any(), any(), any(), any(), any(), any());

        GenerationTriggerResponse response = service.generateForUser(28L, null);

        assertThat(response.status()).isEqualTo("accepted");
        assertThat(response.id()).isNotBlank();
    }

    /* ===== 사용자 선택 topic 경로 (2026-08-06 계약: body {topic} + 관심사 원자 처리) ===== */

    @Test
    @DisplayName("topic 을 지정하면 위키 조회 없이 그 주제로 접수하고, 관심사에 원자 추가한다")
    void requestedTopicSkipsWikiAndEnsuresInterest() {
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn("job-7");

        GenerationTriggerResponse response = service.generateForUser(28L, "양자컴퓨팅");

        assertThat(response.status()).isEqualTo("accepted");
        // 대표 관심사 자동 선택 경로를 타지 않는다
        verify(wikiClient, never()).getTags(any(Long.class));
        // 선택 주제가 관심사에 원자 반영된다 (USER 직접 입력, taxonomy 없음)
        ArgumentCaptor<com.bambi.service.interest.dto.InterestRequest> interestCaptor =
                ArgumentCaptor.forClass(com.bambi.service.interest.dto.InterestRequest.class);
        verify(interestService).create(eq(28L), interestCaptor.capture());
        assertThat(interestCaptor.getValue().name()).isEqualTo("양자컴퓨팅");
        assertThat(interestCaptor.getValue().isTaxonomySelection()).isFalse();
        // 생성 요청 topic = 선택 주제
        ArgumentCaptor<GenerationRequest> captor = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(generationClient).requestGeneration(eq(28L), captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("양자컴퓨팅");
    }

    @Test
    @DisplayName("선택 topic 이 이미 내 관심사면(DUPLICATE_RESOURCE) 추가 없이 통과하고 생성한다")
    void duplicateInterestPassesThrough() {
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn("job-7");
        when(interestService.create(eq(28L), any()))
                .thenThrow(new ApiException(ErrorCode.DUPLICATE_RESOURCE, "이미 있는 관심사"));

        GenerationTriggerResponse response = service.generateForUser(28L, "양자컴퓨팅");

        assertThat(response.status()).isEqualTo("accepted");
        verify(generationClient).requestGeneration(eq(28L), any());
    }

    @Test
    @DisplayName("관심사 반영이 중복 외 사유로 실패하면 생성하지 않는다 (선택 주제 = 관심사 포함이 전제)")
    void interestFailureBlocksGeneration() {
        when(interestService.create(eq(28L), any()))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_ERROR, "이름이 너무 깁니다"));

        assertThatThrownBy(() -> service.generateForUser(28L, "너무 긴 이름"))
                .isInstanceOf(ApiException.class);

        verify(generationClient, never()).requestGeneration(any(Long.class), any());
    }

    @Test
    @DisplayName("빈/공백 topic 은 미지정으로 보고 대표 관심사 자동 선택으로 폴백한다")
    void blankTopicFallsBackToTopInterest() {
        when(wikiClient.getTags(28L)).thenReturn(tagsWith("SK하이닉스"));
        when(generationClient.requestGeneration(eq(28L), any())).thenReturn("job-1");

        service.generateForUser(28L, "   ");

        ArgumentCaptor<GenerationRequest> captor = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(generationClient).requestGeneration(eq(28L), captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("SK하이닉스");
        verify(interestService, never()).create(any(Long.class), any());
    }
}

