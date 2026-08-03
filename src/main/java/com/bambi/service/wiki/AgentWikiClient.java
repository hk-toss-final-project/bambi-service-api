package com.bambi.service.wiki;

import com.bambi.service.agent.AgentErrors;
import com.bambi.service.wiki.dto.WikiDocumentsResponse;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import com.bambi.service.wiki.dto.WikiTopNodesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * agent-api 개인 Wiki 조회 GET 중계 클라이언트.
 *
 * <p>쓰기 경계인 {@link com.bambi.service.agent.AgentGateway} 와 달리 이건 읽기 전용이라 AI 로그를
 * 남기지 않는다(사용자 화면 조회는 처리 Job 이 아니라 단순 질의). agent 오류는 팀 공통 에러로 변환하되,
 * "아직 위키/관심이 없는 사용자"(404)는 오류가 아니라 빈 결과로 정규화한다.
 *
 * <p>user_id 는 agent 계약상 문자열이라 Spring 의 long userId 를 경로에 그대로 문자열로 붙인다.
 */
@Component
public class AgentWikiClient {

    private final RestClient restClient;
    private final String internalPrefix;

    public AgentWikiClient(RestClient agentRestClient,
                           @Value("${app.agent.internal-prefix}") String internalPrefix) {
        this.restClient = agentRestClient;
        this.internalPrefix = internalPrefix;
    }

    /** 활성 관심 태그. 아직 없으면(agent 404) 빈 목록으로 돌려준다. */
    public WikiTagsResponse getTags(long userId) {
        return getOrEmpty("/users/" + userId + "/interests", WikiTagsResponse.class, WikiTagsResponse.empty());
    }

    /** 개인 Wiki 문서 목록(내부 schema 문서 포함 — 제외는 서비스가 한다). */
    public WikiDocumentsResponse getDocuments(long userId) {
        return get("/users/" + userId + "/wiki/documents", WikiDocumentsResponse.class);
    }

    /** 연결 상위 위키 Node. limit 은 agent 계약상 1~100. */
    public WikiTopNodesResponse getTopNodes(long userId, int limit) {
        return get("/users/" + userId + "/wiki/graph/top-nodes?limit=" + limit, WikiTopNodesResponse.class);
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
        try {
            return restClient.get()
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
