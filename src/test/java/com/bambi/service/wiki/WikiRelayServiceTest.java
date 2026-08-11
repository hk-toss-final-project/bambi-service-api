package com.bambi.service.wiki;

import com.bambi.service.wiki.dto.WikiDocumentDetailResponse;
import com.bambi.service.wiki.dto.WikiTag;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import com.bambi.service.wiki.dto.WikiTopNodesResponse;
import com.bambi.service.wiki.dto.WikiResetResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WikiRelayService} — 연결 상위 노드 limit 을 agent 계약 범위(1~100)로 잘라 맞추는지 검증.
 */
class WikiRelayServiceTest {

    private final AgentWikiClient wikiClient = mock(AgentWikiClient.class);
    private final BlockedWikiTagRepository blockedTags = mock(BlockedWikiTagRepository.class);
    private final WikiRelayService service = new WikiRelayService(wikiClient, blockedTags);

    @Test
    @DisplayName("top-nodes: 범위 안 limit 은 그대로 전달한다")
    void topNodesPassesInRangeLimit() {
        when(wikiClient.getTopNodes(eq(1L), anyInt())).thenReturn(new WikiTopNodesResponse(0, List.of()));

        service.topNodes(1L, 10);

        verify(wikiClient).getTopNodes(1L, 10);
    }

    @Test
    @DisplayName("top-nodes: 0·음수 limit 은 1 로 올려 맞춘다")
    void topNodesClampsLowerBound() {
        when(wikiClient.getTopNodes(eq(1L), anyInt())).thenReturn(new WikiTopNodesResponse(0, List.of()));

        service.topNodes(1L, 0);
        service.topNodes(1L, -5);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(wikiClient, org.mockito.Mockito.times(2)).getTopNodes(eq(1L), limit.capture());
        assertThat(limit.getAllValues()).containsExactly(1, 1);
    }

    @Test
    @DisplayName("top-nodes: 100 초과 limit 은 100 으로 내려 맞춘다")
    void topNodesClampsUpperBound() {
        when(wikiClient.getTopNodes(eq(1L), anyInt())).thenReturn(new WikiTopNodesResponse(0, List.of()));

        service.topNodes(1L, 500);

        verify(wikiClient).getTopNodes(1L, 100);
    }

    @Test
    @DisplayName("문서 상세: 인증 주체 ID와 문서 ID를 Agent 조회에 그대로 사용한다")
    void documentUsesAuthenticatedUserScope() {
        WikiDocumentDetailResponse detail = new WikiDocumentDetailResponse(
                "node-1", "version-1", "entity", "node", "entities/node.md", "other",
                "Node", "요약", 1, 0, "2026-07-22T03:15:18Z", "## Node", List.of(), List.of());
        when(wikiClient.getDocument(9L, "node-1")).thenReturn(detail);

        assertThat(service.document(9L, "node-1")).isSameAs(detail);
        verify(wikiClient).getDocument(9L, "node-1");
    }

    @Test
    @DisplayName("Wiki 초기화: 인증 사용자 ID를 Agent에 그대로 전달한다")
    void resetUsesAuthenticatedUserScope() {
        WikiResetResponse reset = new WikiResetResponse(
                "9", 1, 2, 3, 4, 6, 4, 1, 1, 0,
                "2026-08-10T00:00:00Z", "request-1");
        when(wikiClient.reset(9L)).thenReturn(reset);

        assertThat(service.reset(9L)).isSameAs(reset);
        verify(wikiClient).reset(9L);
    }

    // ---- 발견 관심사 숨기기 (V27, 2026-08-11 우석) ----------------------------

    @Test
    @DisplayName("숨긴 태그는 목록에서 빠진다 — 이름 정규화(대소문자·공백) 기준")
    void blockedTagsAreFilteredOut() {
        WikiTagsResponse fromAgent = new WikiTagsResponse("p", 1, "active", null, List.of(
                new WikiTag("id-1", " AI·머신러닝 ", "topic", 0.9, 0.5, List.of(), java.util.Map.of()),
                new WikiTag("id-2", "반도체", "topic", 0.8, 0.5, List.of(), java.util.Map.of())));
        when(wikiClient.getTags(7L)).thenReturn(fromAgent);
        when(blockedTags.findNamesByUserId(7L)).thenReturn(List.of("ai·머신러닝"));

        WikiTagsResponse result = service.tags(7L);

        assertThat(result.tags()).extracting(WikiTag::tag).containsExactly("반도체");
    }

    @Test
    @DisplayName("숨긴 태그가 없으면 agent 응답을 그대로 통과시킨다")
    void noBlockedTagsPassesThrough() {
        WikiTagsResponse fromAgent = new WikiTagsResponse("p", 1, "active", null, List.of(
                new WikiTag("id-1", "반도체", "topic", 0.8, 0.5, List.of(), java.util.Map.of())));
        when(wikiClient.getTags(7L)).thenReturn(fromAgent);
        when(blockedTags.findNamesByUserId(7L)).thenReturn(List.of());

        assertThat(service.tags(7L)).isSameAs(fromAgent);
    }
}
