package com.bambi.service.wiki;

import com.bambi.service.wiki.dto.WikiDocumentDetailResponse;
import com.bambi.service.wiki.dto.WikiDocumentsResponse;
import com.bambi.service.wiki.dto.WikiGraphResponse;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import com.bambi.service.wiki.dto.WikiTopNodesResponse;
import com.bambi.service.wiki.dto.WikiResetResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 개인 Wiki 조회 중계 — agent 응답을 사용자 화면용으로 다듬는다.
 * 문서 목록의 내부 schema를 제외하고 Graph·문서 상세는 인증 사용자 범위 그대로 통과시킨다.
 */
@Service
public class WikiRelayService {

    // 연결 상위 노드 limit 은 agent 계약상 1~100.
    private static final int MIN_TOP_NODES = 1;
    private static final int MAX_TOP_NODES = 100;

    private final AgentWikiClient wikiClient;
    private final BlockedWikiTagRepository blockedTagRepository;

    public WikiRelayService(AgentWikiClient wikiClient,
                            BlockedWikiTagRepository blockedTagRepository) {
        this.wikiClient = wikiClient;
        this.blockedTagRepository = blockedTagRepository;
    }

    /**
     * 자동 추출 관심 태그 — 사용자가 숨긴 태그는 빼고 내려준다(V27, 2026-08-11 우석).
     *
     * <p>숨김은 <b>service 소유</b>다 — agent 는 위키 재계산 때마다 같은 주제를 다시 뽑으므로
     * agent 쪽에서 지우는 것으로는 유지되지 않는다. 이름(정규화) 기준으로 거른다.
     */
    @Transactional(readOnly = true)
    public WikiTagsResponse tags(long userId) {
        WikiTagsResponse response = wikiClient.getTags(userId);
        Set<String> blocked = Set.copyOf(blockedTagRepository.findNamesByUserId(userId));
        if (blocked.isEmpty() || response.tags() == null) {
            return response;
        }
        List<com.bambi.service.wiki.dto.WikiTag> visible = response.tags().stream()
                .filter(tag -> !blocked.contains(BlockedWikiTag.normalize(tag.tag())))
                .toList();
        return new WikiTagsResponse(response.profileId(), response.version(), response.status(),
                response.calculatedAt(), visible);
    }

    /** 발견 관심사 숨기기 — 멱등(이미 숨겨져 있어도 성공). */
    @Transactional
    public void blockTag(long userId, String tagName) {
        blockedTagRepository.insertIgnore(userId, BlockedWikiTag.normalize(tagName));
    }

    /** 숨김 해제 — 멱등(없어도 성공). 내 관심사로 추가할 때도 함께 풀어 목록이 어긋나지 않게 한다. */
    @Transactional
    public void unblockTag(long userId, String tagName) {
        blockedTagRepository.deleteByUserIdAndTagName(userId, BlockedWikiTag.normalize(tagName));
    }

    /** 저장 자료 목록 — 내부 schema 문서는 화면에 안 보이게 제외한다. */
    public WikiDocumentsResponse documents(long userId) {
        return wikiClient.getDocuments(userId).withoutSchema();
    }

    /** 인증 사용자의 전체 LLM Wiki Graph를 그대로 중계한다. */
    public WikiGraphResponse graph(long userId) {
        return wikiClient.getGraph(userId);
    }

    /** 인증 사용자가 소유한 LLM Wiki 문서 상세를 중계한다. */
    public WikiDocumentDetailResponse document(long userId, String documentId) {
        return wikiClient.getDocument(userId, documentId);
    }

    /** 연결 상위 노드 — 범위를 벗어난 limit(0·음수·100 초과)은 계약 범위 1~100 으로 잘라 맞춘다. */
    public WikiTopNodesResponse topNodes(long userId, int limit) {
        int safeLimit = Math.min(MAX_TOP_NODES, Math.max(MIN_TOP_NODES, limit));
        return wikiClient.getTopNodes(userId, safeLimit);
    }

    /** 인증 사용자의 원본을 영구 삭제하고 개인 LLM Wiki 상태를 초기화한다. */
    public WikiResetResponse reset(long userId) {
        return wikiClient.reset(userId);
    }
}
