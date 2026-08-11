package com.bambi.service.wiki;

import com.bambi.service.agent.AgentErrors;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.wiki.dto.BriefingTopicsSelection;
import com.bambi.service.wiki.dto.WikiDocumentDetailResponse;
import com.bambi.service.wiki.dto.WikiDocumentsResponse;
import com.bambi.service.wiki.dto.WikiGraphResponse;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import com.bambi.service.wiki.dto.WikiTopNodesResponse;
import com.bambi.service.wiki.dto.WikiResetResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * agent-api 개인 Wiki 조회·초기화 중계 클라이언트.
 *
 * <p>Wiki 초기화도 LLM 호출이나 생성 Job이 아닌 동기 상태 변경이라 AI 로그를 남기지 않는다.
 * agent 오류는 팀 공통 에러로 변환하되,
 * 아직 활성 관심 Profile이 없는 경우만 빈 결과로, 없는 문서 상세는 Service 표준 404로 변환한다.
 *
 * <p>user_id 는 agent 계약상 문자열이라 Spring 의 long userId 를 경로에 그대로 문자열로 붙인다.
 */
@Component
public class AgentWikiClient {

    private final RestClient restClient;
    private final RestClient selectionRestClient;
    private final String internalPrefix;

    public AgentWikiClient(RestClient agentRestClient,
                           @Qualifier("agentSelectionRestClient") RestClient agentSelectionRestClient,
                           @Value("${app.agent.internal-prefix}") String internalPrefix) {
        this.restClient = agentRestClient;
        this.selectionRestClient = agentSelectionRestClient;
        this.internalPrefix = internalPrefix;
    }

    /** 활성 관심 태그. 아직 없으면(agent 404) 빈 목록으로 돌려준다. */
    public WikiTagsResponse getTags(long userId) {
        return getOrEmpty("/users/" + userId + "/interests", WikiTagsResponse.class, WikiTagsResponse.empty());
    }

    /**
     * 아침 브리핑에 쓸 주제 — agent 가 개인 Wiki 맥락을 읽어 고른다 (2026-08-11 계약).
     *
     * <p>위키가 없는 사용자는 agent 가 404 를 준다. 그건 오류가 아니라 <b>정상 상태</b>라
     * 빈 결과로 바꿔 돌려주고, 호출부가 등록 관심사 폴백으로 넘어간다({@code getTags} 와 같은 정책).
     *
     * <p><b>이 호출만 타임아웃이 길다.</b> agent 안에서 LLM 이 돌기 때문에 다른 위키 조회와 같은
     * 3초로는 매번 타임아웃이 나고, 그러면 전원이 폴백으로 떨어져 "전환했는데 안 바뀐" 상태가 된다.
     * 그래서 {@code agentSelectionRestClient} 를 쓴다 — 나머지 호출은 짧은 쪽 그대로다.
     *
     * @param limit agent 계약상 1~5
     */
    public BriefingTopicsSelection getBriefingTopics(long userId, int limit) {
        return getOrEmpty(selectionRestClient,
                "/users/" + userId + "/briefing-topics?limit=" + limit,
                BriefingTopicsSelection.class, BriefingTopicsSelection.empty());
    }

    /** 개인 Wiki 문서 목록(내부 schema 문서 포함 — 제외는 서비스가 한다). */
    public WikiDocumentsResponse getDocuments(long userId) {
        return get("/users/" + userId + "/wiki/documents", WikiDocumentsResponse.class);
    }

    /** 현재 Entity·Concept 전체 Graph를 조회한다. */
    public WikiGraphResponse getGraph(long userId) {
        return get("/users/" + userId + "/wiki/graph", WikiGraphResponse.class);
    }

    /** 문서 한 건의 Markdown·원본·관계 상세를 조회한다. */
    public WikiDocumentDetailResponse getDocument(long userId, String documentId) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(internalPrefix)
                            .path("/users/{userId}/wiki/documents/{documentId}")
                            .build(userId, documentId))
                    .retrieve()
                    .body(WikiDocumentDetailResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new ApiException(ErrorCode.NOT_FOUND, "LLM Wiki 문서를 찾을 수 없습니다.");
            }
            throw AgentErrors.unavailable(e, "agent 위키 문서 조회 실패");
        } catch (RestClientException e) {
            throw AgentErrors.connectFailed(e);
        }
    }

    /** 연결 상위 위키 Node. limit 은 agent 계약상 1~100. */
    public WikiTopNodesResponse getTopNodes(long userId, int limit) {
        return get("/users/" + userId + "/wiki/graph/top-nodes?limit=" + limit, WikiTopNodesResponse.class);
    }

    /** 사용자 원본을 영구 삭제하고 개인 LLM Wiki 상태를 초기화한다. */
    public WikiResetResponse reset(long userId) {
        try {
            return restClient.delete()
                    .uri(internalPrefix + "/users/" + userId + "/wiki")
                    .retrieve()
                    .body(WikiResetResponse.class);
        } catch (RestClientResponseException e) {
            throw AgentErrors.unavailable(e, "agent 위키 초기화 실패");
        } catch (RestClientException e) {
            throw AgentErrors.connectFailed(e);
        }
    }

    private <T> T get(String pathSuffix, Class<T> type) {
        try {
            return restClient.get()
                    .uri(internalPrefix + pathSuffix)
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException e) {
            throw AgentErrors.unavailable(e, "agent 위키 조회 실패");
        } catch (RestClientException e) {
            throw AgentErrors.connectFailed(e);
        }
    }

    private <T> T getOrEmpty(String pathSuffix, Class<T> type, T emptyValue) {
        return getOrEmpty(restClient, pathSuffix, type, emptyValue);
    }

    private <T> T getOrEmpty(RestClient client, String pathSuffix, Class<T> type, T emptyValue) {
        try {
            return client.get()
                    .uri(internalPrefix + pathSuffix)
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException e) {
            // 아직 활성 Profile이 없는 사용자 = 정상(빈 결과). 그 외 상태만 오류로 올린다.
            if (e.getStatusCode().value() == 404) {
                return emptyValue;
            }
            throw AgentErrors.unavailable(e, "agent 위키 조회 실패");
        } catch (RestClientException e) {
            throw AgentErrors.connectFailed(e);
        }
    }
}
